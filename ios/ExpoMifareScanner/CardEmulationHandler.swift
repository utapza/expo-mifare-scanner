import Foundation
import os.log

/**
 * CardEmulationHandler.swift
 * Mirrors Android CardEmulationService.java logic for Type 4 NDEF tag emulation.
 * Handles APDU: SELECT AID (D2760000850101), SELECT FILE (E103/E104), READ BINARY, GET DATA.
 * References: CardEmulationService.java (createCapabilityContainer, createNdefTextRecord, processCommandApdu, isHexString).
 */

// MARK: - Constants (mirror CardEmulationService.java)

private let tag = "CardEmulation"

// NFC Forum Type 4 Tag Application AID (SELECT_APDU in Java)
private let selectAidBytes: [UInt8] = [0x00, 0xA4, 0x04, 0x00, 0x07, 0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01]

private let selectOk: [UInt8] = [0x90, 0x00]
private let fileNotFound: [UInt8] = [0x6A, 0x82]
private let wrongParameters: [UInt8] = [0x6B, 0x00]

// File IDs: E103 = CC, E104 = NDEF (FILE_ID_CC, FILE_ID_NDEF in Java)
private let fileIdCc: [UInt8] = [0xE1, 0x03]
private let fileIdNdef: [UInt8] = [0xE1, 0x04]

/// Thread-safe holder for Type 4 NDEF emulation state. Mirrors CardEmulationService.java static state + processCommandApdu.
public final class CardEmulationHandler: @unchecked Sendable {
  private let lock = NSLock()
  private var _cardUid: String?
  private var _ccFile: Data?
  private var _ndefFile: Data?
  private var _selectedFile: Int // -1 none, 0 CC, 1 NDEF

  public init() {
    _selectedFile = -1
    os_log(.info, log: .default, "[%{public}@] CardEmulationHandler initialized (Type 4 NDEF)", tag)
  }

  // MARK: - Public API (mirror setCardData, clearCardData, getCardUid)

  /// Set card data to emulate. Logic mirrors CardEmulationService.setCardData().
  /// - If data is long even-length hex → treat as raw NDEF message bytes.
  /// - Else → wrap as NDEF Text record (language "en", UTF-8) via createNdefTextRecord.
  public func setCardData(uid: String, data: String?) {
    lock.lock()
    defer { lock.unlock() }
    _cardUid = uid
    _selectedFile = -1
    guard let data = data, !data.isEmpty else {
      os_log(.default, log: .default, "[%{public}@] Card data set - UID: %{public}@, Data is empty", tag, uid)
      _ccFile = nil
      _ndefFile = nil
      return
    }
    do {
      let ndefMessageBytes: Data
      if Self.isHexString(data) {
        ndefMessageBytes = Self.hexStringToBytes(data)
        os_log(.info, log: .default, "[%{public}@] Card data set (hex/raw NDEF) - UID: %{public}@, NDEF length: %d", tag, uid, ndefMessageBytes.count)
      } else {
        os_log(.info, log: .default, "[%{public}@] Card data set (JSON/Text) - wrapping as NDEF Text record", tag)
        ndefMessageBytes = Self.createNdefTextRecord(text: data, languageCode: "en")
        os_log(.info, log: .default, "[%{public}@] Created NDEF Text record - length: %d", tag, ndefMessageBytes.count)
      }
      let ndefFileSize = 2 + ndefMessageBytes.count
      _ccFile = Self.createCapabilityContainer(ndefFileSize: ndefFileSize)
      var ndefFile = Data(count: 2 + ndefMessageBytes.count)
      ndefFile[0] = UInt8((ndefMessageBytes.count >> 8) & 0xFF)
      ndefFile[1] = UInt8(ndefMessageBytes.count & 0xFF)
      ndefFile.replaceSubrange(2..., with: ndefMessageBytes)
      _ndefFile = ndefFile
      os_log(.info, log: .default, "[%{public}@] CC file: %d bytes, NDEF file: %d bytes", tag, _ccFile!.count, _ndefFile!.count)
    } catch {
      os_log(.error, log: .default, "[%{public}@] Error creating Type 4 tag data: %{public}@", tag, String(describing: error))
      _ccFile = nil
      _ndefFile = nil
    }
  }

  /// Clear card data. Mirrors CardEmulationService.clearCardData().
  public func clearCardData() {
    lock.lock()
    defer { lock.unlock() }
    _cardUid = nil
    _ccFile = nil
    _ndefFile = nil
    _selectedFile = -1
    os_log(.info, log: .default, "[%{public}@] Card data cleared (CC and NDEF files)", tag)
  }

  /// Current card UID. Mirrors CardEmulationService.getCardUid().
  public func getCardUid() -> String? {
    lock.lock()
    defer { lock.unlock() }
    return _cardUid
  }

  /// Returns true if we have UID and file data loaded (emulation “active” from JS perspective).
  public func hasCardData() -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return _cardUid != nil && _ccFile != nil && _ndefFile != nil
  }

  // MARK: - APDU processing (mirror processCommandApdu in CardEmulationService.java)

  /// Process one command APDU and return response bytes (including status word).
  /// Call from CardSession’s .received(cardAPDU) → apdu.respond(with: responseData).
  public func processCommandApdu(_ commandApdu: Data) -> Data {
    guard !commandApdu.isEmpty else {
      os_log(.default, log: .default, "[%{public}@] Received null or empty APDU command", tag)
      return Data(wrongParameters)
    }
    os_log(.debug, log: .default, "[%{public}@] Received APDU: %{public}@ (length: %d)", tag, commandApdu.map { String(format: "%02x", $0) }.joined(), commandApdu.count)

    lock.lock()
    let cc = _ccFile
    let ndef = _ndefFile
    let cardUid = _cardUid
    lock.unlock()

    if cc == nil || ndef == nil || cardUid == nil {
      os_log(.default, log: .default, "[%{public}@] No card data available for emulation", tag)
      if commandApdu.count >= 5, commandApdu[0] == 0x00, commandApdu[1] == 0xA4 {
        return Data(selectOk)
      }
      return Data(wrongParameters)
    }

    // SELECT AID (P1=04) or SELECT FILE (P1=00 or 0C)
    if commandApdu.count >= 5, commandApdu[0] == 0x00, commandApdu[1] == 0xA4 {
      let p1 = commandApdu[2]
      if p1 == 0x04 {
        lock.lock()
        _selectedFile = -1
        lock.unlock()
        os_log(.info, log: .default, "[%{public}@] SELECT AID command received", tag)
        return Data(selectOk)
      }
      if p1 == 0x00 || p1 == 0x0C, commandApdu.count >= 7 {
        let fileId = [commandApdu[5], commandApdu[6]]
        lock.lock()
        if fileId == fileIdCc {
          _selectedFile = 0
          lock.unlock()
          os_log(.info, log: .default, "[%{public}@] SELECT FILE: CC file (E103) selected", tag)
          return Data(selectOk)
        }
        if fileId == fileIdNdef {
          _selectedFile = 1
          lock.unlock()
          os_log(.info, log: .default, "[%{public}@] SELECT FILE: NDEF file (E104) selected", tag)
          return Data(selectOk)
        }
        _selectedFile = -1
        lock.unlock()
        os_log(.default, log: .default, "[%{public}@] SELECT FILE: Unknown file ID", tag)
        return Data(fileNotFound)
      }
      if commandApdu.count < 7 {
        os_log(.default, log: .default, "[%{public}@] SELECT FILE: Invalid command length", tag)
        return Data(wrongParameters)
      }
    }

    // READ BINARY (00 B0)
    if commandApdu.count >= 5, commandApdu[0] == 0x00, commandApdu[1] == 0xB0 {
      let offset = (Int(commandApdu[2]) << 8) | Int(commandApdu[3])
      var length = commandApdu.count > 4 ? Int(commandApdu[4]) : 0
      if length == 0 { length = 256 }
      lock.lock()
      let sel = _selectedFile
      let ccFile = _ccFile
      let ndefFile = _ndefFile
      lock.unlock()
      var targetFile: Data?
      var fileType = ""
      var readOffset = offset
      if sel == 0 {
        targetFile = ccFile
        fileType = "CC"
      } else if sel == 1 {
        targetFile = ndefFile
        fileType = "NDEF"
      } else {
        if offset < 15 {
          targetFile = ccFile
          fileType = "CC (inferred)"
        } else {
          targetFile = ndefFile
          fileType = "NDEF (inferred)"
          readOffset = offset - 15
        }
      }
      if let file = targetFile, !file.isEmpty {
        let actualLength = min(length, file.count - readOffset)
        if actualLength <= 0 || readOffset >= file.count {
          os_log(.default, log: .default, "[%{public}@] READ BINARY: Invalid offset/length for %{public}@", tag, fileType)
          return Data(wrongParameters)
        }
        var response = Data()
        response.append(file.subdata(in: readOffset..<(readOffset + actualLength)))
        response.append(contentsOf: selectOk)
        os_log(.info, log: .default, "[%{public}@] READ BINARY: Returning %d bytes from %{public}@ (offset: %d)", tag, actualLength, fileType, readOffset)
        return response
      }
      os_log(.default, log: .default, "[%{public}@] READ BINARY: No file data available", tag)
      return Data(wrongParameters)
    }

    // GET DATA (00 CA) - return UID
    if commandApdu.count >= 5, commandApdu[0] == 0x00, commandApdu[1] == 0xCA {
      os_log(.info, log: .default, "[%{public}@] GET DATA command received, returning UID", tag)
      lock.lock()
      let uid = _cardUid
      lock.unlock()
      if let uid = uid, let uidBytes = Self.hexStringToBytesOptional(uid) {
        var response = Data(uidBytes)
        response.append(contentsOf: selectOk)
        return response
      }
    }

    os_log(.debug, log: .default, "[%{public}@] Unknown command, returning FILE_NOT_FOUND", tag)
    return Data(fileNotFound)
  }

  // MARK: - Helpers (mirror Java static helpers)

  /// createCapabilityContainer in CardEmulationService.java
  private static func createCapabilityContainer(ndefFileSize: Int) -> Data {
    var cc = Data(count: 15)
    cc[0] = 0x00
    cc[1] = 0x0F
    cc[2] = 0xFF
    cc[3] = 0x20
    return cc
  }

  /// createNdefTextRecord in CardEmulationService.java — TNF=1, Type "T", language "en", UTF-8
  private static func createNdefTextRecord(text: String, languageCode: String) -> Data {
    let textBytes = Array(text.utf8)
    let langBytes = Array(languageCode.utf8)
    let payloadLength = 1 + langBytes.count + textBytes.count
    let flags: UInt8 = 0xD1
    let typeLength: UInt8 = 1
    let payloadLengthByte = UInt8(payloadLength & 0xFF)
    let typeT: UInt8 = 0x54
    let statusByte = UInt8(langBytes.count)
    var payload = Data()
    payload.append(statusByte)
    payload.append(contentsOf: langBytes)
    payload.append(contentsOf: textBytes)
    var ndefRecord = Data()
    ndefRecord.append(flags)
    ndefRecord.append(typeLength)
    ndefRecord.append(payloadLengthByte)
    ndefRecord.append(typeT)
    ndefRecord.append(payload)
    return ndefRecord
  }

  /// isHexString in CardEmulationService.java
  private static func isHexString(_ str: String) -> Bool {
    let s = str.trimmingCharacters(in: .whitespacesAndNewlines)
    guard s.count >= 2, s.count % 2 == 0 else { return false }
    let hexChars = CharacterSet(charactersIn: "0123456789aAbBcCdDeEfF")
    guard s.unicodeScalars.allSatisfy({ hexChars.contains($0) }) else { return false }
    if s.count > 64 { return true }
    if s.hasPrefix("{") || s.contains(" ") { return false }
    return true
  }

  private static func hexStringToBytes(_ hex: String) -> Data {
    let h = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    var data = Data()
    var i = h.startIndex
    while i < h.endIndex {
      let next = h.index(i, offsetBy: 2, limitedBy: h.endIndex) ?? h.endIndex
      let sub = String(h[i..<next])
      if let byte = UInt8(sub, radix: 16) {
        data.append(byte)
      }
      i = next
    }
    return data
  }

  private static func hexStringToBytesOptional(_ hex: String) -> Data? {
    let h = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    guard h.count >= 2, h.count % 2 == 0 else { return nil }
    var data = Data()
    var i = h.startIndex
    while i < h.endIndex {
      let next = h.index(i, offsetBy: 2, limitedBy: h.endIndex) ?? h.endIndex
      let sub = String(h[i..<next])
      guard let byte = UInt8(sub, radix: 16) else { return nil }
      data.append(byte)
      i = next
    }
    return data
  }
}
