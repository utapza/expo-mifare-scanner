const { withAndroidManifest } = require('@expo/config-plugins');

/**
 * Expo config plugin for ExpoMifareScanner
 * Adds NFC permissions and features to AndroidManifest.xml
 */
const withMifareScanner = (config) => {
  return withAndroidManifest(config, async (config) => {
    const androidManifest = config.modResults;
    const { manifest } = androidManifest;

    // Ensure permissions array exists
    if (!manifest['uses-permission']) {
      manifest['uses-permission'] = [];
    }

    // Add NFC permission if not already present
    const hasNfcPermission = manifest['uses-permission'].some(
      (perm) => perm.$['android:name'] === 'android.permission.NFC'
    );
    if (!hasNfcPermission) {
      manifest['uses-permission'].push({
        $: { 'android:name': 'android.permission.NFC' },
      });
    }

    // Add NFC feature if not already present
    if (!manifest['uses-feature']) {
      manifest['uses-feature'] = [];
    }
    const hasNfcFeature = manifest['uses-feature'].some(
      (feat) => feat.$['android:name'] === 'android.hardware.nfc'
    );
    if (!hasNfcFeature) {
      manifest['uses-feature'].push({
        $: { 'android:name': 'android.hardware.nfc', 'android:required': 'false' },
      });
    }

    return config;
  });
};

module.exports = withMifareScanner;
