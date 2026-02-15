import { requireNativeModule, EventEmitter } from 'expo-modules-core';

// Import the native module
let ExpoMifareScanner;
let isExpoGo = false;
try {
  ExpoMifareScanner = requireNativeModule('ExpoMifareScanner');
} catch (e) {
  ExpoMifareScanner = null;
  // Check if we're running in Expo Go (which doesn't support custom native modules)
  try {
    const { Constants } = require('expo-constants');
    isExpoGo = Constants.executionEnvironment === 'storeClient';
  } catch (constantsError) {
    // If expo-constants is not available, we can't definitively detect Expo Go
    // But the error message will still be helpful
    isExpoGo = false;
  }
  
  // Log a warning (not an error) - this is expected in Expo Go
  if (isExpoGo) {
    console.warn('ExpoMifareScanner: Custom native modules are not supported in Expo Go. Please use a development build.');
  } else {
    console.warn('ExpoMifareScanner: Native module not found. This feature requires a development build.');
  }
}

const emitter = new EventEmitter(ExpoMifareScanner || {});

export function addCardScannedListener(listener) {
  return emitter.addListener('onCardScanned', listener);
}

export function removeCardScannedListener(subscription) {
  if (subscription && subscription.remove) {
    subscription.remove();
  }
}

export async function startScanning() {
  if (!ExpoMifareScanner) {
    const errorMessage = isExpoGo 
      ? 'ExpoMifareScanner requires a development build. Custom native modules are not supported in Expo Go.'
      : 'ExpoMifareScanner native module not available. Please rebuild the app with a development build.';
    throw new Error(errorMessage);
  }
  return await ExpoMifareScanner.startScanning();
}

export async function stopScanning() {
  if (!ExpoMifareScanner) {
    // Silently fail if module is not available (already stopped)
    return;
  }
  return await ExpoMifareScanner.stopScanning();
}

export async function isNfcEnabled() {
  if (!ExpoMifareScanner) {
    return false;
  }
  return await ExpoMifareScanner.isNfcEnabled();
}

/**
 * Read NFC tag - simplified async API
 * Starts scanning, waits for tag discovery, automatically stops, and returns data
 * 
 * @param {Object} options - Optional configuration
 * @param {number} options.timeout - Timeout in milliseconds (default: 30000 = 30 seconds)
 * @returns {Promise<Object>} Promise that resolves with card data or rejects with error
 * 
 * @example
 * try {
 *   const cardData = await readNfcTag({ timeout: 10000 });
 *   console.log('Card UID:', cardData.uid);
 *   console.log('Card Data:', cardData.data);
 * } catch (error) {
 *   console.error('Scan failed:', error.message);
 * }
 */
export async function readNfcTag(options = {}) {
  console.log('[ExpoMifareScanner] readNfcTag() called with options:', options);
  const { timeout = 30000 } = options;

  if (!ExpoMifareScanner) {
    console.error('[ExpoMifareScanner] ExpoMifareScanner is null/undefined');
    const errorMessage = isExpoGo 
      ? 'ExpoMifareScanner requires a development build. Custom native modules are not supported in Expo Go.'
      : 'ExpoMifareScanner native module not available. Please rebuild the app with a development build.';
    throw new Error(errorMessage);
  }

  console.log('[ExpoMifareScanner] Checking if NFC is enabled...');
  // Check if NFC is enabled
  const nfcEnabled = await isNfcEnabled();
  console.log('[ExpoMifareScanner] NFC enabled:', nfcEnabled);
  if (!nfcEnabled) {
    throw new Error('NFC is not enabled on this device. Please enable NFC in settings.');
  }

  console.log('[ExpoMifareScanner] Creating Promise for NFC scan...');
  return new Promise((resolve, reject) => {
    let scanSubscription = null;
    let timeoutId = null;
    let isResolved = false;
    
    console.log('[ExpoMifareScanner] Promise executor started');

    // Cleanup function
    const cleanup = () => {
      console.log('[ExpoMifareScanner] cleanup() called');
      if (timeoutId) {
        clearTimeout(timeoutId);
        timeoutId = null;
        console.log('[ExpoMifareScanner] Cleared timeout');
      }
      if (scanSubscription) {
        removeCardScannedListener(scanSubscription);
        scanSubscription = null;
        console.log('[ExpoMifareScanner] Removed scan subscription');
      }
      // Stop scanning (best effort, don't wait for it)
      if (ExpoMifareScanner) {
        console.log('[ExpoMifareScanner] Calling stopScanning()');
        try {
          const stopResult = ExpoMifareScanner.stopScanning();
          console.log('[ExpoMifareScanner] stopScanning() returned:', typeof stopResult, stopResult);
          
          // Check if it's a Promise before calling .catch()
          if (stopResult && typeof stopResult === 'object' && typeof stopResult.catch === 'function') {
            stopResult.catch((err) => {
              console.log('[ExpoMifareScanner] stopScanning() error (ignored):', err);
            });
          } else {
            console.log('[ExpoMifareScanner] stopScanning() did not return a Promise, skipping .catch()');
          }
        } catch (error) {
          console.log('[ExpoMifareScanner] Error calling stopScanning():', error);
        }
      } else {
        console.log('[ExpoMifareScanner] ExpoMifareScanner is null, skipping stopScanning()');
      }
    };

    // Set up timeout
    timeoutId = setTimeout(() => {
      if (!isResolved) {
        isResolved = true;
        cleanup();
        reject(new Error(`NFC scan timed out after ${timeout}ms. Please try again.`));
      }
    }, timeout);

    // Set up event listener
    try {
      console.log('[ExpoMifareScanner] Setting up card scanned listener...');
      scanSubscription = addCardScannedListener((event) => {
        console.log('[ExpoMifareScanner] Card scanned event received:', event);
        if (isResolved) {
          console.log('[ExpoMifareScanner] Already resolved, ignoring event');
          return; // Already resolved/rejected
        }

        console.log('[ExpoMifareScanner] Processing card scan event...');
        isResolved = true;
        cleanup();

        // Format the response
        const cardData = {
          uid: event.uid,
          data: event.data,
          rawData: event.rawData,
          timestamp: event.timestamp,
        };

        console.log('[ExpoMifareScanner] Resolving with card data:', cardData);
        resolve(cardData);
      });
      console.log('[ExpoMifareScanner] Card scanned listener set up, subscription:', scanSubscription);

      // Start scanning
      console.log('[ExpoMifareScanner] Starting scan...');
      (async () => {
        try {
          console.log('[ExpoMifareScanner] Calling startScanning()...');
          const startResult = await startScanning();
          console.log('[ExpoMifareScanner] startScanning() completed:', startResult);
        } catch (error) {
          console.error('[ExpoMifareScanner] Error in startScanning():', error);
          if (!isResolved) {
            isResolved = true;
            cleanup();
            reject(new Error(`Failed to start NFC scanning: ${error.message}`));
          }
        }
      })();
    } catch (error) {
      console.error('[ExpoMifareScanner] Error setting up scan:', error);
      if (!isResolved) {
        isResolved = true;
        cleanup();
        reject(new Error(`Failed to set up NFC scan: ${error.message}`));
      }
    }
  });
}
