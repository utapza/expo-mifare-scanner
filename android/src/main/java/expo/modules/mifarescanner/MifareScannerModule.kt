package expo.modules.mifarescanner

import android.app.Activity
import android.nfc.NfcAdapter
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.events.EventsDefinition

class MifareScannerModule : Module() {
  private val TAG = "MifareScannerModule"
  private var nfcAdapter: NfcAdapter? = null
  private var currentActivity: Activity? = null
  private var mifareScanner: MifareScanner? = null

  override fun definition() = ModuleDefinition {
    Name("ExpoMifareScanner")

    Events("onCardScanned")

    OnCreate {
      currentActivity = appContext.currentActivity
      nfcAdapter = NfcAdapter.getDefaultAdapter(appContext.reactContext)
      
      if (nfcAdapter != null && currentActivity != null) {
        mifareScanner = MifareScanner(nfcAdapter!!, currentActivity!!)
        mifareScanner?.setOnCardScannedListener { uid, data, rawData, timestamp ->
          sendEvent("onCardScanned", mapOf(
            "uid" to uid,
            "data" to data,
            "rawData" to rawData,
            "timestamp" to timestamp
          ))
        }
      }
    }

    OnActivityEntersForeground {
      currentActivity = appContext.currentActivity
      mifareScanner?.updateActivity(currentActivity)
    }

    Function("startScanning") {
      try {
        val scanner = mifareScanner
        if (scanner == null) {
          throw Exception("MIFARE scanner is not initialized. NFC adapter may not be available.")
        }
        scanner.startScanning()
      } catch (e: Exception) {
        throw Exception("Failed to start NFC scanning: ${e.message}", e)
      }
    }

    Function("stopScanning") {
      try {
        mifareScanner?.stopScanning()
      } catch (e: Exception) {
        // Log but don't throw - stopping is best effort
        android.util.Log.e(TAG, "Error stopping scan: ${e.message}", e)
      }
    }

    Function("isNfcEnabled") {
      try {
        mifareScanner?.isNfcEnabled() ?: false
      } catch (e: Exception) {
        android.util.Log.e(TAG, "Error checking NFC status: ${e.message}", e)
        false
      }
    }

    Function("startCardEmulation") { uid: String, data: String ->
      try {
        val scanner = mifareScanner
        if (scanner == null) {
          throw Exception("MIFARE scanner is not initialized. NFC adapter may not be available.")
        }
        scanner.startCardEmulation(uid, data)
      } catch (e: Exception) {
        throw Exception("Failed to start card emulation: ${e.message}", e)
      }
    }

    Function("stopCardEmulation") {
      try {
        val scanner = mifareScanner
        if (scanner == null) {
          throw Exception("MIFARE scanner is not initialized. NFC adapter may not be available.")
        }
        scanner.stopCardEmulation()
      } catch (e: Exception) {
        throw Exception("Failed to stop card emulation: ${e.message}", e)
      }
    }

    Function("isCardEmulationActive") {
      try {
        val scanner = mifareScanner
        if (scanner == null) {
          return@Function false
        }
        scanner.isCardEmulationActive()
      } catch (e: Exception) {
        android.util.Log.e(TAG, "Error checking card emulation status: ${e.message}", e)
        false
      }
    }
  }
}
