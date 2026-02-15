# @utapza/expo-mifare-scanner

Expo module for scanning MIFARE Classic NFC cards on Android.

## Features

- ✅ Scan MIFARE Classic cards
- ✅ Read NDEF records
- ✅ Read raw data from multiple sectors
- ✅ Automatic JSON extraction
- ✅ Simple async API
- ✅ Automatic start/stop handling
- ✅ Timeout support
- ✅ Comprehensive error handling

## Installation

```bash
npm install @utapza/expo-mifare-scanner
```

**Note**: This module requires a development build. It does not work in Expo Go.

## Usage

### Simple API (Recommended)

```javascript
import { readNfcTag } from '@utapza/expo-mifare-scanner';

try {
  // Start scanning, wait for tag, automatically stop
  const cardData = await readNfcTag({ timeout: 10000 });
  
  console.log('Card UID:', cardData.uid);
  console.log('Card Data:', cardData.data); // JSON string
  console.log('Raw Data:', cardData.rawData); // Hex string
  console.log('Timestamp:', cardData.timestamp);
} catch (error) {
  console.error('Scan failed:', error.message);
  // Possible errors:
  // - "NFC is not enabled on this device"
  // - "NFC scan timed out after 10000ms"
  // - "Failed to start NFC scanning"
}
```

### Advanced API (Event-based)

If you need more control, you can use the event-based API:

```javascript
import { 
  startScanning, 
  stopScanning, 
  addCardScannedListener,
  removeCardScannedListener,
  isNfcEnabled 
} from '@utapza/expo-mifare-scanner';

// Check if NFC is enabled
const enabled = await isNfcEnabled();

if (enabled) {
  // Set up listener
  const subscription = addCardScannedListener((event) => {
    console.log('Card scanned:', event);
    // Stop scanning when done
    stopScanning();
  });

  // Start scanning
  await startScanning();

  // Later, clean up
  removeCardScannedListener(subscription);
  await stopScanning();
}
```

## API Reference

### `readNfcTag(options?)`

Simplified async function that handles the entire scan lifecycle.

**Parameters:**
- `options.timeout` (number, optional): Timeout in milliseconds. Default: 30000 (30 seconds)

**Returns:** `Promise<CardData>`

**CardData:**
```typescript
{
  uid: string;        // Card UID (hex string)
  data: string;       // Card data (JSON string or text)
  rawData: string;    // Raw hex data
  timestamp: number;  // Unix timestamp
}
```

**Throws:**
- `Error` if NFC is not enabled
- `Error` if scan times out
- `Error` if scanning fails to start
- `Error` if module is not available (Expo Go)

### `isNfcEnabled()`

Check if NFC is enabled on the device.

**Returns:** `Promise<boolean>`

### `startScanning()`

Start NFC scanning (for advanced use cases).

**Throws:** `Error` if module is not available

### `stopScanning()`

Stop NFC scanning (for advanced use cases).

### `addCardScannedListener(listener)`

Add event listener for card scans (for advanced use cases).

**Parameters:**
- `listener` (function): Callback that receives `{ uid, data, rawData, timestamp }`

**Returns:** Subscription object with `remove()` method

### `removeCardScannedListener(subscription)`

Remove event listener (for advanced use cases).

## Requirements

- Android device with NFC support
- Development build (does not work in Expo Go)
- NFC enabled on device
- Expo SDK 54+

## Development Build

Since this uses custom native code, you need a development build:

```bash
# Install dependencies
npm install

# Generate native code
npx expo prebuild --platform android

# Build and run
npx expo run:android
```

## How It Works

1. **NDEF Reading**: First attempts to read NDEF records (standard NFC format)
2. **MIFARE Reading**: If NDEF fails, reads multiple sectors from MIFARE Classic card
3. **Data Extraction**: Automatically extracts JSON from the data
4. **Auto-cleanup**: Automatically stops scanning after tag is discovered

## Error Handling

The module throws descriptive errors:

- `"NFC is not enabled on this device"` - User needs to enable NFC
- `"NFC scan timed out after Xms"` - No tag detected within timeout
- `"Failed to start NFC scanning"` - System error starting scan
- `"ExpoMifareScanner requires a development build"` - Running in Expo Go

## License

MIT
