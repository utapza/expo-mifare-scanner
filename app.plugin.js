const { withAndroidManifest, withDangerousMod } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/**
 * Expo config plugin for ExpoMifareScanner
 * Adds NFC permissions, features, HCE service to AndroidManifest.xml
 * and copies required resource files
 */
const withMifareScanner = (config) => {
  // Step 1: Modify AndroidManifest.xml
  config = withAndroidManifest(config, async (config) => {
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

  // Step 2: Copy apduservice.xml resource file
  config = withDangerousMod(config, [
    'android',
    async (config) => {
      const projectRoot = config.modRequest.platformProjectRoot;
      const xmlDir = path.join(projectRoot, 'app', 'src', 'main', 'res', 'xml');
      
      // Ensure xml directory exists
      if (!fs.existsSync(xmlDir)) {
        fs.mkdirSync(xmlDir, { recursive: true });
      }

      // Path to destination in the app
      const destXml = path.join(xmlDir, 'apduservice.xml');

      // Try to find the source file - check multiple possible locations
      let sourceXml = null;
      
      // Method 1: Try to resolve from node_modules (if installed as npm package)
      try {
        const modulePath = require.resolve('@utapza/expo-mifare-scanner/package.json');
        const moduleDir = path.dirname(modulePath);
        const candidatePath = path.join(moduleDir, 'android', 'src', 'main', 'res', 'xml', 'apduservice.xml');
        if (fs.existsSync(candidatePath)) {
          sourceXml = candidatePath;
        }
      } catch (e) {
        // Module not found in node_modules, try other methods
      }

      // Method 2: Try relative to plugin file location (for local development)
      if (!sourceXml) {
        const pluginDir = __dirname;
        const candidatePath = path.join(pluginDir, 'android', 'src', 'main', 'res', 'xml', 'apduservice.xml');
        if (fs.existsSync(candidatePath)) {
          sourceXml = candidatePath;
        }
      }

      // Copy the file if source exists, otherwise create it
      if (sourceXml && fs.existsSync(sourceXml)) {
        fs.copyFileSync(sourceXml, destXml);
        console.log(`[expo-mifare-scanner] Copied apduservice.xml from ${sourceXml} to ${destXml}`);
      } else {
        // Create the file if it doesn't exist
        const apduContent = `<?xml version="1.0" encoding="utf-8"?>
<host-apdu-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/apdu_service_description"
    android:requireDeviceUnlock="false">
    <aid-group android:description="@string/aid_group_description"
        android:category="other">
        <!-- MIFARE Classic AID -->
        <aid-filter android:name="F0394148148100"/>
        <!-- Generic NFC AID -->
        <aid-filter android:name="D2760000850101"/>
    </aid-group>
</host-apdu-service>
`;
        fs.writeFileSync(destXml, apduContent);
        console.log(`[expo-mifare-scanner] Created apduservice.xml at ${destXml}`);
      }

      return config;
    },
  ]);

  // Step 3: Add string resources to strings.xml
  config = withDangerousMod(config, [
    'android',
    async (config) => {
      const projectRoot = config.modRequest.platformProjectRoot;
      const stringsPath = path.join(projectRoot, 'app', 'src', 'main', 'res', 'values', 'strings.xml');
      
      let stringsContent = '';
      if (fs.existsSync(stringsPath)) {
        stringsContent = fs.readFileSync(stringsPath, 'utf8');
      } else {
        // Create basic strings.xml if it doesn't exist
        stringsContent = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>';
      }

      // Check if strings already exist
      const hasApduDesc = stringsContent.includes('apdu_service_description');
      const hasAidDesc = stringsContent.includes('aid_group_description');

      if (!hasApduDesc || !hasAidDesc) {
        // Parse and add strings
        // Simple approach: add before closing </resources> tag
        const closingTag = '</resources>';
        const insertBefore = stringsContent.lastIndexOf(closingTag);
        
        if (insertBefore !== -1) {
          let additions = '';
          if (!hasApduDesc) {
            additions += '  <string name="apdu_service_description">MIFARE Card Emulation Service</string>\n';
          }
          if (!hasAidDesc) {
            additions += '  <string name="aid_group_description">MIFARE Classic Card Emulation</string>\n';
          }
          
          stringsContent = 
            stringsContent.slice(0, insertBefore) + 
            additions + 
            stringsContent.slice(insertBefore);
          
          fs.writeFileSync(stringsPath, stringsContent, 'utf8');
          console.log(`[expo-mifare-scanner] Updated strings.xml at ${stringsPath}`);
        }
      }

      return config;
    },
  ]);

  return config;
};

module.exports = withMifareScanner;
