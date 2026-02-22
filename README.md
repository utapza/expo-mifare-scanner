# @utapza/expo-mifare-scanner

Expo module for scanning NFC cards and emulating NFC Forum Type 4 Tags with NDEF content on Android.

## Features

### Scanning
- ✅ Scan MIFARE Classic cards
- ✅ Read NDEF records (prioritized)
- ✅ Read raw data from multiple sectors
- ✅ Automatic JSON extraction
- ✅ Simple async API
- ✅ Automatic start/stop handling
- ✅ Timeout support
- ✅ Comprehensive error handling

### Card Emulation
- ✅ Emulate NFC Forum Type 4 Tag (ISO-14443-4 / ISO 7816-4)
- ✅ Serve NDEF content via Host Card Emulation (HCE)
- ✅ Automatic NDEF Text record encoding from JSON
- ✅ Support for raw NDEF message bytes
- ✅ Capability Container (CC) and NDEF file handling
- ✅ Compatible with standard NFC readers (NFC Tools, modern phones)

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

### Scanning NFC Tags

#### Simple API (Recommended)

```javascript
import { readNfcTag } from '@utapza/expo-mifare-scanner';

try {
  // Start scanning, wait for tag, automatically stop
  const cardData = await readNfcTag({ timeout: 10000 });
  
  console.log('Card UID:', cardData.uid);
  console.log('Card Data:', cardData.data); // JSON string
  console.log('Raw Data:', cardData.rawData); // Hex string (NDEF message bytes if available)
  console.log('Timestamp:', cardData.timestamp);
} catch (error) {
  console.error('Scan failed:', error.message);
  // Possible errors:
  // - "NFC is not enabled on this device"
  // - "NFC scan timed out after 10000ms"
  // - "Failed to start NFC scanning"
}
```

### Emulating NFC Tags (Type 4 NDEF)

The module can emulate an NFC Forum Type 4 Tag that serves NDEF content. This allows your phone to act as a smart NDEF tag.

```javascript
import { emulateCard } from '@utapza/expo-mifare-scanner';

// Example: Emulate a student ID card with JSON data
const studentData = {
  name: "Ayabonga Qwabi",
  studentId: "UTAP-WSU-20250123",
  university: "Walter Sisulu University",
  faculty: "Engineering and Technology",
  cardNumber: "WSU-99887766",
  issuer: "Walter Sisulu University",
  issueDate: "2024-01-15",
  expiryDate: "2026-12-31",
  cardType: "student_id"
};

try {
  // Start emulation with JSON data (automatically wrapped as NDEF Text record)
  await emulateCard(
    { enabled: true },
    '12345678', // UID (hex string) - Note: Android HCE uses random UID
    JSON.stringify(studentData) // JSON will be wrapped as NDEF Text record
  );
  
  console.log('Card emulation started! Phone will act as NFC tag.');
  
  // Stop emulation when done
  await emulateCard({ enabled: false });
} catch (error) {
  console.error('Emulation failed:', error.message);
}
```

#### Using Raw NDEF Bytes

If you already have NDEF message bytes (e.g., from scanning a tag), you can use them directly:

```javascript
// rawData from a scanned tag contains NDEF message bytes
const cardData = await readNfcTag({ timeout: 10000 });

// Use the raw NDEF bytes for emulation
await emulateCard(
  { enabled: true },
  cardData.uid,
  cardData.rawData // Hex string of NDEF message bytes
);
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

### `emulateCard(options, uid?, data?)`

Start or stop card emulation. The phone will act as an NFC Forum Type 4 Tag serving NDEF content.

**Parameters:**
- `options.enabled` (boolean, required): `true` to start emulation, `false` to stop
- `uid` (string, optional): Card UID (hex string). Required when `enabled: true`. Note: Android HCE cannot set custom UIDs (random UID will be used)
- `data` (string, optional): Card data. Required when `enabled: true`. Can be:
  - JSON string: Will be automatically wrapped as NDEF Text record (TNF=1, type="T", language="en", UTF-8)
  - Hex string: Raw NDEF message bytes (preferred if already encoded)

**Returns:** `Promise<void>`

**Throws:**
- `Error` if module is not available (Expo Go)
- `Error` if UID or data is missing when starting emulation
- `Error` if emulation fails to start

**Example:**
```javascript
// Start emulation with JSON
await emulateCard(
  { enabled: true },
  '12345678',
  JSON.stringify({ studentId: '12345', name: 'John Doe' })
);

// Stop emulation
await emulateCard({ enabled: false });
```

### `isCardEmulationActive()`

Check if card emulation is currently active.

**Returns:** `Promise<boolean>`

## Requirements

- Android device with NFC support
- Android device with HCE support (for card emulation)
- Development build (does not work in Expo Go)
- NFC enabled on device
- Expo SDK 54+

## Complete Examples

### Scanning Example

Here's a complete example of scanning NFC tags:

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

### Card Emulation Example

Here's a complete example of emulating an NFC tag:

```javascript
import React, { useState, useEffect } from 'react';
import { View, Button, Text, Alert } from 'react-native';
import { emulateCard, isCardEmulationActive } from '@utapza/expo-mifare-scanner';

export default function EmulateScreen() {
  const [emulating, setEmulating] = useState(false);

  useEffect(() => {
    // Check emulation status on mount
    checkEmulationStatus();
  }, []);

  const checkEmulationStatus = async () => {
    const active = await isCardEmulationActive();
    setEmulating(active);
  };

  const handleStartEmulation = async () => {
    try {
      const studentData = {
        name: "Ayabonga Qwabi",
        studentId: "UTAP-WSU-20250123",
        university: "Walter Sisulu University",
        faculty: "Engineering and Technology",
        cardNumber: "WSU-99887766",
        cardType: "student_id"
      };

      await emulateCard(
        { enabled: true },
        '12345678', // UID (note: Android HCE uses random UID)
        JSON.stringify(studentData)
      );
      
      setEmulating(true);
      Alert.alert('Success', 'Card emulation started! Tap your phone on an NFC reader.');
    } catch (error) {
      Alert.alert('Error', error.message);
    }
  };

  const handleStopEmulation = async () => {
    try {
      await emulateCard({ enabled: false });
      setEmulating(false);
      Alert.alert('Success', 'Card emulation stopped.');
    } catch (error) {
      Alert.alert('Error', error.message);
    }
  };

  return (
    <View style={{ padding: 20 }}>
      <Text style={{ fontSize: 18, marginBottom: 20 }}>
        Status: {emulating ? 'Emulating' : 'Stopped'}
      </Text>
      
      {!emulating ? (
        <Button 
          title="Start Emulation" 
          onPress={handleStartEmulation}
        />
      ) : (
        <Button 
          title="Stop Emulation" 
          onPress={handleStopEmulation}
        />
      )}
      
      <Text style={{ marginTop: 20, fontSize: 12, color: 'gray' }}>
        Note: Android HCE uses a random UID. Your phone will act as an NFC Forum Type 4 Tag serving NDEF content.
      </Text>
    </View>
  );
}
```

## How It Works

### Scanning

1. **NDEF Reading**: First attempts to read NDEF records (standard NFC format) - prioritized for Type 4 Tag support
2. **MIFARE Reading**: If NDEF fails, reads multiple sectors from MIFARE Classic card as fallback
3. **Data Extraction**: Automatically extracts JSON from the data
4. **Auto-cleanup**: Automatically stops scanning after tag is discovered

### Card Emulation

1. **NDEF Encoding**: JSON strings are automatically wrapped as NDEF Text records (TNF=1, type="T", language="en", UTF-8)
2. **Type 4 Tag Structure**: Creates Capability Container (CC) file and NDEF file following ISO-14443-4 / ISO 7816-4 standard
3. **Host Card Emulation (HCE)**: Uses Android HCE to serve NDEF content via standard APDU commands
4. **Reader Compatibility**: Works with readers that support Type 4 / NDEF (NFC Tools, modern phones, some access points)

**Note**: Android HCE cannot set custom UIDs - the phone will use a random UID when emulating.

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
   # Or for emulation logs:
   adb logcat | grep -i "CardEmulation"
   ```

### Card Emulation Not Working

If card emulation doesn't work:

1. **Check HCE is supported**:
   - Most modern Android devices support HCE
   - Some devices may require specific settings

2. **Verify emulation is active**:
   ```javascript
   const active = await isCardEmulationActive();
   console.log('Emulation active:', active);
   ```

3. **Check reader compatibility**:
   - Use NFC Tools app or another phone to test
   - Some access control readers may not support Type 4 / NDEF

4. **Check logs**:
   ```bash
   adb logcat | grep -i "CardEmulation"
   ```

5. **Note about UID**: Android HCE uses random UIDs - you cannot set a custom UID. This is an Android limitation.

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
