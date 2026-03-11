const { withAndroidManifest, withDangerousMod, withInfoPlist, withEntitlementsPlist, withXcodeProject } = require('@expo/config-plugins');
const { getProjectName } = require('@expo/config-plugins/build/ios/utils/Xcodeproj');
const { addBuildSourceFileToGroup } = require('@expo/config-plugins/build/ios/utils/Xcodeproj');
const fs = require('fs');
const path = require('path');

/**
 * Expo config plugin for ExpoMifareScanner
 * - Android: NFC permissions, features, HCE service, apduservice.xml
 * - iOS: NFCReaderUsageDescription (Info.plist), NFC + HCE entitlements (comments + optional merge)
 *
 * iOS entitlements (must be granted in Apple Developer / provisioning profile):
 * - com.apple.developer.nfc.hce = true
 * - com.apple.developer.nfc.hce.iso7816.select-identifier-prefixes = ["D2760000850101"]
 * App targets iOS 18.4+ (CardSession) and requires eligible region (e.g. South Africa) for HCE.
 */
const withMifareScanner = (config) => {
  // ----- iOS: Info.plist (NFC usage description) -----
  config = withInfoPlist(config, (c) => {
    const plist = c.modResults;
    // Required for CoreNFC (NFCTagReaderSession / NFCNDEFReaderSession)
    if (!plist.NFCReaderUsageDescription) {
      plist.NFCReaderUsageDescription = 'This app uses NFC to read and emulate NFC tags for card access.';
    }
    // Optional: ISO7816 select identifiers for tag reading (Type 4 / NDEF)
    if (!plist['com.apple.developer.nfc.readersession.iso7816.select-identifiers']) {
      plist['com.apple.developer.nfc.readersession.iso7816.select-identifiers'] = ['D2760000850101'];
    }
    return c;
  });

  // ----- iOS: Entitlements (developer must have HCE entitlement from Apple) -----
  // Merges into the app's entitlements file. Only add keys that are safe to set;
  // com.apple.developer.nfc.hce and iso7816.select-identifier-prefixes are
  // managed by Apple and must be in the provisioning profile.
  config = withEntitlementsPlist(config, (c) => {
    const ent = c.modResults;
    // NFC Tag Reading capability (reader session). Use TAG only; NDEF is disallowed with SDK 26 / min OS 15.1 per App Store validation.
    if (ent['com.apple.developer.nfc.readersession.formats'] == null) {
      ent['com.apple.developer.nfc.readersession.formats'] = ['TAG'];
    }
    // Uncomment below only if your provisioning profile already includes HCE:
    // ent['com.apple.developer.nfc.hce'] = true;
    // ent['com.apple.developer.nfc.hce.iso7816.select-identifier-prefixes'] = ['D2760000850101'];
    return c;
  });

  // ----- iOS: Copy Swift module files into the app target and add to Xcode project -----
  // When expo-modules-autolinking doesn't include the module (e.g. EAS installs npm package
  // without full ios/), the config plugin copies the Swift files and links them so the app compiles the module.
  config = withDangerousMod(config, [
    'ios',
    async (config) => {
      const projectRoot = config.modRequest.projectRoot;
      const platformRoot = config.modRequest.platformProjectRoot;
      let projectName;
      try {
        projectName = getProjectName(projectRoot);
      } catch (e) {
        console.warn('[expo-mifare-scanner] Could not get iOS project name:', e?.message);
        return config;
      }
      let moduleDir;
      try {
        const pkgPath = require.resolve('@utapza/expo-mifare-scanner/package.json');
        moduleDir = path.dirname(pkgPath);
      } catch (e) {
        moduleDir = __dirname;
      }
      const sourceDir = path.join(moduleDir, 'ios', 'ExpoMifareScanner');
      const swiftFiles = ['ExpoMifareScannerModule.swift', 'CardEmulationHandler.swift'];
      const destDir = path.join(platformRoot, projectName);
      for (const name of swiftFiles) {
        const src = path.join(sourceDir, name);
        if (fs.existsSync(src)) {
          const dest = path.join(destDir, name);
          fs.copyFileSync(src, dest);
          console.log(`[expo-mifare-scanner] Copied ${name} to Xcode project`);
        } else {
          console.warn(`[expo-mifare-scanner] Missing Swift file: ${src}`);
        }
      }
      return config;
    },
  ]);

  config = withXcodeProject(config, (config) => {
    const projectRoot = config.modRequest.projectRoot;
    let project = config.modResults;
    let projectName;
    try {
      projectName = getProjectName(projectRoot);
    } catch (e) {
      return config;
    }
    const swiftFiles = ['ExpoMifareScannerModule.swift', 'CardEmulationHandler.swift'];
    for (const name of swiftFiles) {
      const filepath = `${projectName}/${name}`;
      if (!project.hasFile(filepath)) {
        project = addBuildSourceFileToGroup({
          filepath,
          groupName: projectName,
          project,
        });
      }
    }
    config.modResults = project;
    return config;
  });

  // ----- Android: Step 1 - Modify AndroidManifest.xml -----
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
      (service) => service.$ && service.$['android:name'] === 'expo.modules.mifarescanner.CardEmulationService'
    );

    if (!hasHceService) {
      application.service.push({
        $: {
          'android:name': 'expo.modules.mifarescanner.CardEmulationService',
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

  // Step 2 (Android): Copy apduservice.xml resource file
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
        // Changed for Type 4 NDEF support: Use NFC Forum Type 4 Tag AID only
        const apduContent = `<?xml version="1.0" encoding="utf-8"?>
<host-apdu-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/apdu_service_description"
    android:requireDeviceUnlock="false">
    <aid-group android:description="@string/aid_group_description"
        android:category="other">
        <!-- NFC Forum Type 4 Tag Application AID -->
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

  // Step 3 (Android): Add string resources to strings.xml
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
          // Changed for Type 4 NDEF support: Updated descriptions for NFC Forum Type 4 Tag emulation
          if (!hasApduDesc) {
            additions += '  <string name="apdu_service_description">NFC Type 4 Tag Emulation Service</string>\n';
          }
          if (!hasAidDesc) {
            additions += '  <string name="aid_group_description">NFC Forum Type 4 Tag with NDEF</string>\n';
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
