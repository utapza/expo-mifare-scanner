const { withAndroidManifest } = require('@expo/config-plugins');

/**
 * Expo config plugin for ExpoMifareScanner
 * Adds NFC permissions, features, and HCE service to AndroidManifest.xml
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

    // Add HCE feature
    const hasHceFeature = manifest['uses-feature'].some(
      (feat) => feat.$['android:name'] === 'android.hardware.nfc.hce'
    );
    if (!hasHceFeature) {
      manifest['uses-feature'].push({
        $: { 'android:name': 'android.hardware.nfc.hce', 'android:required': 'false' },
      });
    }

    // Add HCE service to application
    if (!manifest.application) {
      manifest.application = [{}];
    }
    const application = manifest.application[0];
    
    if (!application.service) {
      application.service = [];
    }

    // Check if HCE service already exists
    const hasHceService = application.service.some(
      (service) => service.$ && service.$['android:name'] === 'expo.modules.mifarescanner.MifareCardEmulationService'
    );

    if (!hasHceService) {
      application.service.push({
        $: {
          'android:name': 'expo.modules.mifarescanner.MifareCardEmulationService',
          'android:exported': 'true',
          'android:permission': 'android.permission.BIND_NFC_SERVICE',
        },
        'intent-filter': [
          {
            action: [
              {
                $: { 'android:name': 'android.nfc.cardemulation.action.HOST_APDU_SERVICE' },
              },
            ],
          },
        ],
        'meta-data': [
          {
            $: {
              'android:name': 'android.nfc.cardemulation.host_apdu_service',
              'android:resource': '@xml/apduservice',
            },
          },
        ],
      });
    }

    return config;
  });
};

module.exports = withMifareScanner;
