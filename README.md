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

## Setup

### 1. Install the Package

```bash
npm install @utapza/expo-mifare-scanner
```

### 2. Configure app.json

Add the module's config plugin to your `app.json`:

```json
{
  "expo": {
    "plugins": [
      "@utapza/expo-mifare-scanner"
    ]
  }
}
```

The plugin automatically:
- Adds NFC permissions to AndroidManifest.xml
- Configures the module for your app

### 3. Generate Native Code

After installing and configuring, regenerate your native code:

```bash
npx expo prebuild --platform android --clean
```

### 4. Build Development Build

Since this uses custom native code, you need a development build:

```bash
# Build and run on Android
npx expo run:android

# Or build APK
cd android && ./gradlew assembleDebug
```

### 5. Verify Installation

Check that the module is properly linked:

```bash
# Check if module is in node_modules
ls node_modules/@utapza/expo-mifare-scanner

# Verify in Android build
cd android && ./gradlew projects | grep mifare
```

You should see `:expo-mifare-scanner` in the project list.

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

## Complete Example

Here's a complete example of integrating the module into your app:

```javascript
import React, { useState } from 'react';
import { View, Button, Text, Alert } from 'react-native';
import { readNfcTag, isNfcEnabled } from '@utapza/expo-mifare-scanner';

export default function ScanScreen() {
  const [scanning, setScanning] = useState(false);
  const [cardData, setCardData] = useState(null);

  const handleScan = async () => {
    try {
      // Check if NFC is enabled
      const enabled = await isNfcEnabled();
      if (!enabled) {
        Alert.alert('NFC Disabled', 'Please enable NFC in your device settings.');
        return;
      }

      setScanning(true);
      
      // Scan for card (automatically starts, waits, and stops)
      const data = await readNfcTag({ timeout: 15000 });
      
      setCardData(data);
      Alert.alert('Success', `Card scanned! UID: ${data.uid}`);
    } catch (error) {
      Alert.alert('Error', error.message);
    } finally {
      setScanning(false);
    }
  };

  return (
    <View style={{ padding: 20 }}>
      <Button 
        title={scanning ? 'Scanning...' : 'Scan Card'} 
        onPress={handleScan}
        disabled={scanning}
      />
      {cardData && (
        <View style={{ marginTop: 20 }}>
          <Text>UID: {cardData.uid}</Text>
          <Text>Data: {cardData.data}</Text>
        </View>
      )}
    </View>
  );
}
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

## Troubleshooting

### Module Not Found Error

If you see `"Cannot find native module 'ExpoMifareScanner'"`:

1. **Make sure you're using a development build** (not Expo Go):
   ```bash
   npx expo run:android
   ```

2. **Verify the module is installed**:
   ```bash
   ls node_modules/@utapza/expo-mifare-scanner
   ```

3. **Regenerate native code**:
   ```bash
   npx expo prebuild --platform android --clean
   ```

4. **Rebuild the app**:
   ```bash
   npx expo run:android
   ```

### Plugin Not Applied

If NFC permissions are missing:

1. **Check app.json** has the plugin:
   ```json
   {
     "plugins": ["@utapza/expo-mifare-scanner"]
   }
   ```

2. **Regenerate native code**:
   ```bash
   npx expo prebuild --platform android --clean
   ```

3. **Check AndroidManifest.xml** (in `android/app/src/main/AndroidManifest.xml`):
   ```xml
   <uses-permission android:name="android.permission.NFC" />
   ```

### Build Errors

If you get build errors:

1. **Clean build**:
   ```bash
   cd android && ./gradlew clean
   ```

2. **Check Expo SDK version** (requires SDK 54+):
   ```bash
   npx expo --version
   ```

3. **Verify Android SDK**:
   - `minSdkVersion`: 24+
   - `compileSdkVersion`: 35
   - `targetSdkVersion`: 34

### NFC Not Working

If NFC scanning doesn't work:

1. **Check device has NFC**:
   ```javascript
   const enabled = await isNfcEnabled();
   console.log('NFC enabled:', enabled);
   ```

2. **Enable NFC in device settings**

3. **Check app is in foreground** (NFC requires foreground activity)

4. **Check logs**:
   ```bash
   adb logcat | grep -i mifare
   ```

## Migration from Local Module

If you were using a local file dependency:

1. **Update package.json**:
   ```json
   {
     "dependencies": {
       "@utapza/expo-mifare-scanner": "^1.0.0"
     }
   }
   ```

2. **Update app.json**:
   ```json
   {
     "plugins": ["@utapza/expo-mifare-scanner"]
   }
   ```

3. **Update imports**:
   ```javascript
   // Old
   import { readNfcTag } from './expo-modules/expo-mifare-scanner/src/index';
   
   // New
   import { readNfcTag } from '@utapza/expo-mifare-scanner';
   ```

4. **Remove local module** (optional):
   ```bash
   rm -rf expo-modules/expo-mifare-scanner
   ```

5. **Reinstall and rebuild**:
   ```bash
   npm install
   npx expo prebuild --platform android --clean
   npx expo run:android
   ```

## License

MIT
