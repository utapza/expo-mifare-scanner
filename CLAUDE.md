# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

`@utapza/expo-mifare-scanner` is an Expo native module (npm package) that provides:
- **NFC tag scanning** — reads MIFARE Classic cards and NDEF tags on Android and iOS
- **Host Card Emulation (HCE)** — makes the phone act as an NFC Forum Type 4 Tag (ISO-14443-4 / ISO 7816-4) serving NDEF content

This is **not a standalone app**. It is a library consumed by an Expo/React Native app via `npm install @utapza/expo-mifare-scanner`.

## Build & Development Commands

This module has no build script of its own. Development happens in the **consumer app** that installs this package.

### In the consumer app:
```bash
# After changing native code or config plugin, regenerate native projects:
npx expo prebuild --platform android --clean

# Build and run on Android device:
npx expo run:android

# Build APK manually:
cd android && ./gradlew assembleDebug

# Clean Android build:
cd android && ./gradlew clean

# Verify module is linked:
cd android && ./gradlew projects | grep mifare

# Debug NFC scanning logs:
adb logcat | grep -i "MifareScanner\|CardEmulation\|ExpoMifareScanner"
```

### Publishing this package:
```bash
npm publish
```

## Architecture

### Layer Structure

```
src/index.js                        ← JS API (requireNativeModule → native bridge)
├── Android (Kotlin/Java)
│   ├── MifareScannerModule.kt      ← Expo module definition, exposes functions to JS
│   ├── MifareScanner.java          ← NFC scanning logic (NfcAdapter.ReaderCallback)
│   └── CardEmulationService.java   ← HCE service (HostApduService), processes APDU
└── iOS (Swift)
    ├── ExpoMifareScannerModule.swift  ← Expo module, NFCTagReaderSession, CardSession
    └── CardEmulationHandler.swift     ← APDU processing logic (mirrors Java service)
```

### JS API (`src/index.js`)

All functions use `requireNativeModule('ExpoMifareScanner')` from `expo-modules-core`. If the native module isn't available (Expo Go), functions throw descriptive errors.

Key exports:
- `readNfcTag(options?)` — high-level: starts scan, awaits tag, stops, resolves `{ uid, data, rawData, timestamp }`
- `emulateCard(options, uid?, data?)` — starts/stops card emulation; accepts JSON string (auto-wrapped as NDEF Text record) or raw hex NDEF bytes
- `startScanning()` / `stopScanning()` / `isNfcEnabled()` — low-level
- `addCardScannedListener(listener)` / `removeCardScannedListener(subscription)` — event-based API
- `isCardEmulationActive()` — check emulation state

### Android Native

- **`MifareScannerModule.kt`** — Expo `Module` subclass. Registers `Name("ExpoMifareScanner")`, declares the `onCardScanned` event, and delegates to `MifareScanner` and `CardEmulationService`.
- **`MifareScanner.java`** — Uses `NfcAdapter.enableReaderMode()`. Tag handling: first tries NDEF (`Ndef.get(tag)`), then falls back to MIFARE Classic sector reads. Fires `OnCardScannedListener` with `(uid, data, rawData, timestamp)`.
- **`CardEmulationService.java`** — `HostApduService` (Android HCE). Static state (`ccFile`, `ndefFile`, `cardUid`) set via `setCardData()`. Handles APDU sequence: SELECT AID `D2760000850101` → SELECT FILE (CC `E103` or NDEF `E104`) → READ BINARY. JSON input is encoded as an NDEF Text record (TNF=1, type="T", lang="en", UTF-8).

### iOS Native

- **`ExpoMifareScannerModule.swift`** — Expo `Module` subclass. Scanning uses `NFCTagReaderSession` (CoreNFC). Emulation uses `CardSession` (iOS 18.4+, requires `com.apple.developer.nfc.hce` entitlement and eligible region).
- **`CardEmulationHandler.swift`** — Swift port of `CardEmulationService.java`. Identical APDU logic, thread-safe via `NSLock`.

### Config Plugin (`app.plugin.js`)

Modifies the consumer app's native projects during `npx expo prebuild`:
- **Android**: adds NFC permission, NFC/HCE feature declarations, `CardEmulationService` to `AndroidManifest.xml`, copies `apduservice.xml` (AID `D2760000850101`), adds string resources.
- **iOS**: sets `NFCReaderUsageDescription` in `Info.plist`, sets `com.apple.developer.nfc.readersession.formats = ["TAG"]` in entitlements, copies Swift files (`ExpoMifareScannerModule.swift`, `CardEmulationHandler.swift`) into the Xcode target.

## Key Constraints

- **Requires a development build** — does not work in Expo Go.
- **Android HCE**: cannot set custom UID; Android always uses a random UID during emulation.
- **iOS HCE**: requires `CardSession` (iOS 18.4+), `com.apple.developer.nfc.hce` entitlement (granted by Apple), and device must be in an eligible region.
- **Android SDK**: `minSdkVersion` 24, `compileSdkVersion` 35, `targetSdkVersion` 34.
- **Expo SDK**: 54+.

## Data Flow: Card Emulation

1. JS calls `emulateCard({ enabled: true }, uid, data)`
2. `src/index.js` calls `ExpoMifareScanner.startCardEmulation(uid, data)`
3. **Android**: `MifareScanner.startCardEmulation()` → `CardEmulationService.setCardData()` stores CC file + NDEF file in static fields. When an NFC reader taps the phone, Android routes APDUs to `CardEmulationService.processCommandApdu()`.
4. **iOS**: `ExpoMifareScannerModule.startCardEmulation()` → `CardEmulationHandler.setCardData()` → `CardSession` loop responds to APDU events via `CardEmulationHandler.processCommandApdu()`.

## Data Flow: Scanning

1. JS calls `readNfcTag({ timeout })` (or low-level `startScanning()`)
2. **Android**: `MifareScanner.startScanning()` enables reader mode; `handleTag()` tries NDEF first, then MIFARE Classic fallback; fires `onCardScanned` event.
3. **iOS**: `NFCTagReaderSession` begins; `TagReaderDelegate.handleConnectedTag()` reads NDEF if available; fires `onCardScanned` via `sendCardScannedEvent()`.
4. JS `onCardScanned` event resolves the `readNfcTag()` promise with `{ uid, data, rawData, timestamp }`.
