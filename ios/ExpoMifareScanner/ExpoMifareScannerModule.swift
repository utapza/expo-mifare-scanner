/**
 * ExpoMifareScannerModule.swift
 * iOS implementation of ExpoMifareScanner — same public JS API as Android (MifareScannerModule.kt).
 * Scanning: CoreNFC NFCTagReaderSession (mirrors MifareScanner.java handleTag → uid, data, rawData).
 * Emulation: CardSession + CardEmulationHandler (mirrors CardEmulationService.java Type 4 NDEF).
 * References: MifareScanner.java, CardEmulationService.java, MifareScannerModule.kt.
 */

import Foundation
import CoreNFC
import ExpoModulesCore
import Sentry
import os.log

private let tag = "ExpoMifareScannerModule"

// MARK: - Tag reader delegate (NFCTagReaderSession → uid, data, rawData like MifareScanner.handleTag)

private final class TagReaderDelegate: NSObject, NFCTagReaderSessionDelegate {
  weak var module: ExpoMifareScannerModule?
  private var invalidateAfterFirstRead: Bool = false

  func setInvalidateAfterFirstRead(_ value: Bool) { invalidateAfterFirstRead = value }

  func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
    os_log(.info, log: .default, "[%{public}@] Tag reader session active - Discovery", tag)
  }

  func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
    let msg = error.localizedDescription
    os_log(.info, log: .default, "[%{public}@] Tag reader session invalidated: %{public}@", tag, msg)
    module?.onTagReaderSessionInvalidated()
  }

  func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
    guard let first = tags.first else { return }
    os_log(.info, log: .default, "[%{public}@] Tag discovered - Discovery", tag)
    session.connect(to: first) { [weak self] err in
      guard let self = self else { return }
      if let e = err {
        os_log(.error, log: .default, "[%{public}@] Tag connect error: %{public}@", tag, e.localizedDescription)
        session.invalidate(errorMessage: "Connection failed")
        return
      }
      self.handleConnectedTag(session: session, tag: first)
    }
  }

  private func handleConnectedTag(session: NFCTagReaderSession, tag nfcTag: NFCTag) {
    let uidData: Data
    switch nfcTag {
    case .iso7816(let iso):
      uidData = iso.identifier
    case .miFare(let mifare):
      uidData = mifare.identifier
    case .iso15693(let iso):
      uidData = iso.identifier
    case .feliCa(let feliCa):
      uidData = feliCa.currentID
    @unknown default:
      session.invalidate(errorMessage: "Unsupported tag type")
      return
    }
    let uid = uidData.map { String(format: "%02x", $0) }.joined()
    os_log(.info, log: .default, "[%{public}@] Processing tag with UID: %{public}@ - Discovery", tag, uid)

    var data = ""
    var rawData = ""

    // Try to read NDEF if tag conforms to NFCNDEFTag (mirrors MifareScanner.java NDEF path)
    let ndefTag: NFCNDEFTag? = {
      switch nfcTag {
      case .miFare(let t): return t as? NFCNDEFTag
      case .iso7816(let t): return t as? NFCNDEFTag
      default: return nil
      }
    }()
    if let ndef = ndefTag {
      readNDEF(from: ndef) { [weak self] message in
        guard let self = self else { return }
        if let msg = message {
          let (parsed, hex) = self.ndefMessageToDataAndRaw(msg)
          data = parsed
          rawData = hex
          os_log(.info, log: .default, "[%{public}@] NDEF message bytes extracted: %d hex chars - Success", tag, rawData.count)
        }
        self.finishTagAndNotify(session: session, uid: uid, data: data, rawData: rawData)
      }
      return
    }

    finishTagAndNotify(session: session, uid: uid, data: data, rawData: rawData)
  }

  private func readNDEF(from ndefTag: NFCNDEFTag, completion: @escaping (NFCNDEFMessage?) -> Void) {
    ndefTag.readNDEF { msg, err in
      if let e = err {
        os_log(.default, log: .default, "[%{public}@] NDEF read error: %{public}@", tag, e.localizedDescription)
        completion(nil)
        return
      }
      completion(msg)
    }
  }

  private func ndefMessageToDataAndRaw(_ message: NFCNDEFMessage) -> (data: String, rawData: String) {
    var textParts: [String] = []
    for record in message.records {
      if record.typeNameFormat == .nfcWellKnown, record.type == "T".data(using: .utf8) {
        if let text = parseTextRecord(record) { textParts.append(text) }
      }
      if let payload = record.payload, payload.count > 0, let s = String(data: payload, encoding: .utf8), !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        textParts.append(s)
      }
    }
    let data = textParts.joined()
    let rawData = message.toByteArray().map { String(format: "%02x", $0) }.joined()
    return (data, rawData)
  }

  private func parseTextRecord(_ record: NFCNDEFPayload) -> String? {
    guard let payload = record.payload, payload.count >= 1 else { return nil }
    let status = payload[0]
    let langLen = Int(status & 0x3F)
    let utf16 = (status & 0x80) != 0
    guard payload.count > 1 + langLen else { return nil }
    let textData = payload.subdata(in: (1 + langLen)..<payload.count)
    return utf16 ? String(data: textData, encoding: .utf16) : String(data: textData, encoding: .utf8)
  }

  private func finishTagAndNotify(session: NFCTagReaderSession, uid: String, data: String, rawData: String) {
    let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
    os_log(.info, log: .default, "[%{public}@] Sending onCardScanned event - Success", tag)
    module?.sendCardScannedEvent(uid: uid, data: data, rawData: rawData, timestamp: timestamp)
    if invalidateAfterFirstRead {
      session.invalidate()
    } else {
      session.restartPolling()
    }
  }
}

// MARK: - NFCNDEFMessage to bytes (mirror Android NdefMessage.toByteArray() — full NDEF encoding)

private extension NFCNDEFMessage {
  func toByteArray() -> Data {
    var full = Data()
    for (index, record) in records.enumerated() {
      let type = record.type
      let payload = record.payload ?? Data()
      let id = record.identifier
      var flags: UInt8 = 0
      switch record.typeNameFormat {
      case .nfcWellKnown: flags |= 0x01
      case .nfcExternal: flags |= 0x02
      case .nfcMedia: flags |= 0x03
      case .empty: flags |= 0x04
      case .unknown: flags |= 0x05
      case .unchanged: flags |= 0x06
      @unknown default: break
      }
      if index == 0 { flags |= 0x80 } // MB
      if index == records.count - 1 { flags |= 0x40 } // ME
      if payload.count <= 255 { flags |= 0x10 } // SR
      if id.count > 0 { flags |= 0x08 } // IL
      full.append(flags)
      full.append(UInt8(type.count))
      if payload.count <= 255 {
        full.append(UInt8(payload.count))
      } else {
        full.append(0)
        full.append(UInt8((payload.count >> 24) & 0xFF))
        full.append(UInt8((payload.count >> 16) & 0xFF))
        full.append(UInt8((payload.count >> 8) & 0xFF))
        full.append(UInt8(payload.count & 0xFF))
      }
      if id.count > 0 { full.append(UInt8(id.count)) }
      full.append(type)
      full.append(id)
      full.append(payload)
    }
    return full
  }
}

// MARK: - Module

public final class ExpoMifareScannerModule: Module {
  private let cardHandler = CardEmulationHandler()
  private var tagReaderSession: NFCTagReaderSession?
  private var tagReaderDelegate: TagReaderDelegate?
  private var emulationTask: Task<Void, Never>?
  @available(iOS 17.4, *)
  private var cardSession: CardSession?
  private let emulationLock = NSLock()

  public required override init(appContext: AppContext) {
    super.init(appContext: appContext)
    os_log(.info, log: .default, "[%{public}@] ExpoMifareScannerModule init() - native module instance created (JS can require it)", tag)
  }

  public func definition() -> ModuleDefinition {
    os_log(.info, log: .default, "[%{public}@] definition() called - registering as Name(ExpoMifareScanner)", tag)
    Name("ExpoMifareScanner")
    Events("onCardScanned")

    AsyncFunction("startScanning") { [weak self] in
      guard let self = self else { return }
      guard NFCReaderSession.readingAvailable else {
        throw NSError(domain: "ExpoMifareScanner", code: -1, userInfo: [NSLocalizedDescriptionKey: "NFC is not available on this device."])
      }
      self.emulationLock.lock()
      if self.tagReaderSession != nil {
        self.emulationLock.unlock()
        throw NSError(domain: "ExpoMifareScanner", code: -2, userInfo: [NSLocalizedDescriptionKey: "NFC scanning is already in progress"])
      }
      let delegate = TagReaderDelegate()
      delegate.module = self
      delegate.setInvalidateAfterFirstRead(false)
      self.tagReaderDelegate = delegate
      let session = NFCTagReaderSession(pollingOption: [.iso14443, .iso15693], delegate: delegate, queue: nil)
      session.alertMessage = "Hold your iPhone near an NFC tag."
      self.tagReaderSession = session
      self.emulationLock.unlock()
      session.begin()
      os_log(.info, log: .default, "[%{public}@] Starting MIFARE scanning - Discovery", tag)
    }

    AsyncFunction("stopScanning") { [weak self] in
      guard let self = self else { return }
      self.emulationLock.lock()
      defer { self.emulationLock.unlock() }
      if let session = self.tagReaderSession {
        session.invalidate()
        self.tagReaderSession = nil
        self.tagReaderDelegate = nil
        os_log(.info, log: .default, "[%{public}@] Reader mode disabled", tag)
      } else {
        os_log(.info, log: .default, "[%{public}@] Not scanning, ignoring stop request", tag)
      }
    }

    AsyncFunction("isNfcEnabled") { [weak self] () -> Bool in
      os_log(.info, log: .default, "[%{public}@] isNfcEnabled() INVOKED from JS - native side", tag)
      let available = NFCReaderSession.readingAvailable
      os_log(.info, log: .default, "[%{public}@] NFC readingAvailable: %d", tag, available)
      let crumb = Breadcrumb()
      crumb.category = "nfc"
      crumb.message = "NFC Availability Check (available: \(available))"
      SentrySDK.addBreadcrumb(crumb)
      return available
    }

    AsyncFunction("startCardEmulation") { [weak self] (uid: String, data: String) in
      guard let self = self else { return }
      guard #available(iOS 17.4, *) else {
        throw NSError(
          domain: "ExpoMifareScanner",
          code: -3,
          userInfo: [NSLocalizedDescriptionKey: "Card emulation requires iOS 17.4 or newer."]
        )
      }
      guard CardSession.isSupported else {
        throw NSError(
          domain: "ExpoMifareScanner",
          code: -3,
          userInfo: [NSLocalizedDescriptionKey: "Card emulation is not supported on this device."]
        )
      }
      let eligible: Bool
      if #available(iOS 18.0, *) {
        eligible = await CardSession.isEligible
      } else {
        eligible = false
      }
      guard eligible else {
        throw NSError(
          domain: "ExpoMifareScanner",
          code: -4,
          userInfo: [NSLocalizedDescriptionKey: "Card session is not eligible in this region. HCE requires eligible device/region (e.g. South Africa)."]
        )
      }
      self.cardHandler.setCardData(uid: uid, data: data)
      os_log(.info, log: .default, "[%{public}@] Card emulation data set - UID: %{public}@", tag, uid)
      self.emulationLock.lock()
      if self.emulationTask != nil {
        self.emulationLock.unlock()
        throw NSError(
          domain: "ExpoMifareScanner",
          code: -5,
          userInfo: [NSLocalizedDescriptionKey: "Card emulation is already running."]
        )
      }
      self.emulationLock.unlock()
      let task = Task { [weak self] in
        await self?.runCardSession()
      }
      self.emulationLock.lock()
      self.emulationTask = task
      self.emulationLock.unlock()
      os_log(.info, log: .default, "[%{public}@] Card emulation started successfully", tag)
    }

    AsyncFunction("stopCardEmulation") { [weak self] in
      guard let self = self else { return }
      self.cardHandler.clearCardData()
      guard #available(iOS 17.4, *) else {
        return
      }
      self.emulationLock.lock()
      self.cardSession?.invalidate()
      self.cardSession = nil
      self.emulationLock.unlock()
      os_log(.info, log: .default, "[%{public}@] Card emulation stopped", tag)
    }

    AsyncFunction("isCardEmulationActive") { [weak self] () -> Bool in
      guard let self = self else { return false }
      let active = self.cardHandler.hasCardData()
      os_log(.info, log: .default, "[%{public}@] Card emulation active: %d", tag, active)
      return active
    }
  }

  func onTagReaderSessionInvalidated() {
    emulationLock.lock()
    tagReaderSession = nil
    tagReaderDelegate = nil
    emulationLock.unlock()
  }

  func sendCardScannedEvent(uid: String, data: String, rawData: String, timestamp: Int64) {
    sendEvent("onCardScanned", [
      "uid": uid,
      "data": data,
      "rawData": rawData,
      "timestamp": timestamp
    ])
  }

  @available(iOS 17.4, *)
  private func runCardSession() async {
    var presentmentIntent: NFCPresentmentIntentAssertion?
    do {
      if #available(iOS 18.0, *) {
        presentmentIntent = try await NFCPresentmentIntentAssertion.acquire()
      }
      let session = try await CardSession()
      emulationLock.lock()
      cardSession = session
      emulationLock.unlock()
      session.alertMessage = "Communicating with card reader."
      for try await event in session.eventStream {
        switch event {
        case .sessionStarted:
          break
        case .readerDetected:
          try? await session.startEmulation()
        case .readerDeselected:
          await session.stopEmulation(status: .success)
        case .received(let cardAPDU):
          let response = cardHandler.processCommandApdu(cardAPDU.payload)
          do {
            try await cardAPDU.respond(with: response)
          } catch {
            os_log(.error, log: .default, "[%{public}@] APDU respond error: %{public}@", tag, String(describing: error))
          }
        case .sessionInvalidated(reason: _):
          break
        }
      }
    } catch {
      os_log(.error, log: .default, "[%{public}@] CardSession error: %{public}@", tag, String(describing: error))
    }
    presentmentIntent = nil
    emulationLock.lock()
    cardSession = nil
    emulationTask = nil
    emulationLock.unlock()
  }
}
