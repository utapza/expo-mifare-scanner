package expo.modules.mifarescanner;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.Ndef;
import android.util.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MifareScanner {
    private static final String TAG = "MifareScanner";
    private NfcAdapter nfcAdapter;
    private Activity currentActivity;
    private boolean isScanning = false;
    private NfcAdapter.ReaderCallback readerCallback;
    private OnCardScannedListener listener;

    public interface OnCardScannedListener {
        void onCardScanned(String uid, String data, String rawData, long timestamp);
    }

    public MifareScanner(NfcAdapter adapter, Activity activity) {
        this.nfcAdapter = adapter;
        this.currentActivity = activity;
        Log.i(TAG, "MifareScanner initialized - Discovery");
    }

    public void setOnCardScannedListener(OnCardScannedListener listener) {
        this.listener = listener;
        Log.i(TAG, "Card scanned listener set - Discovery");
    }

    public boolean isNfcEnabled() {
        boolean enabled = nfcAdapter != null && nfcAdapter.isEnabled();
        Log.i(TAG, "NFC enabled check: " + enabled + " - Discovery");
        return enabled;
    }

    public void startScanning() throws Exception {
        if (isScanning) {
            String errorMsg = "NFC scanning is already in progress";
            Log.e(TAG, errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (nfcAdapter == null) {
            String errorMsg = "NFC adapter is not available on this device";
            Log.e(TAG, errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (currentActivity == null) {
            String errorMsg = "Activity is not available. Make sure the app is in the foreground.";
            Log.e(TAG, errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (!nfcAdapter.isEnabled()) {
            String errorMsg = "NFC is not enabled on this device. Please enable NFC in settings.";
            Log.e(TAG, errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        isScanning = true;
        Log.i(TAG, "Starting MIFARE scanning - Discovery");

        readerCallback = new NfcAdapter.ReaderCallback() {
            @Override
            public void onTagDiscovered(Tag tag) {
                Log.i(TAG, "Tag discovered - Discovery");
                // Normal read operation
                handleTag(tag);
            }
        };

        int flags = NfcAdapter.FLAG_READER_NFC_A |
                    NfcAdapter.FLAG_READER_NFC_B;

        try {
            nfcAdapter.enableReaderMode(currentActivity, readerCallback, flags, null);
            Log.i(TAG, "Reader mode enabled successfully - Discovery");
        } catch (Exception e) {
            isScanning = false;
            String errorMsg = "Failed to enable NFC reader mode: " + e.getMessage();
            Log.e(TAG, errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    public void stopScanning() {
        if (!isScanning) {
            Log.i(TAG, "Not scanning, ignoring stop request");
            return;
        }

        if (nfcAdapter != null && currentActivity != null) {
            try {
                nfcAdapter.disableReaderMode(currentActivity);
                Log.i(TAG, "Reader mode disabled");
            } catch (Exception e) {
                Log.e(TAG, "Error disabling reader mode: " + e.getMessage(), e);
            }
        }

        isScanning = false;
        readerCallback = null;
    }

    private void handleTag(Tag tag) {
        // Get UID from tag ID (this is the actual UID, not block 0)
        String uid = bytesToHex(tag.getId());
        Log.i(TAG, "Processing tag with UID: " + uid + " - Discovery");

        String data = "";
        String rawData = "";
        String rawMifareData = ""; // Raw MIFARE Classic block data for emulation

        // STEP 1: Always read raw MIFARE Classic blocks first (for emulation)
        // This ensures we have the raw data needed for proper emulation
        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare != null) {
            try {
                mifare.connect();
                Log.i(TAG, "Connected to MIFARE card for raw data reading - Discovery");

                int sectorCount = mifare.getSectorCount();
                Log.i(TAG, "Reading raw MIFARE data from " + sectorCount + " sectors - Reading");
                
                List<Byte> allRawBytes = new ArrayList<>();
                
                // Read all accessible sectors for raw data
                for (int sectorIndex = 0; sectorIndex < Math.min(sectorCount, 16); sectorIndex++) {
                    try {
                        // Try default key authentication
                        boolean authenticated = mifare.authenticateSectorWithKeyA(
                            sectorIndex,
                            MifareClassic.KEY_DEFAULT
                        );
                        
                        if (!authenticated) {
                            authenticated = mifare.authenticateSectorWithKeyB(
                                sectorIndex,
                                MifareClassic.KEY_DEFAULT
                            );
                        }
                        
                        if (!authenticated) {
                            Log.i(TAG, "Cannot authenticate sector " + sectorIndex + " for raw read - Authentication failed");
                            continue;
                        }

                        int firstBlock = mifare.sectorToBlock(sectorIndex);
                        int blockCount = mifare.getBlockCountInSector(sectorIndex);
                        
                        // Read all blocks in sector (including trailer for complete raw data)
                        for (int blockOffset = 0; blockOffset < blockCount; blockOffset++) {
                            int blockNumber = firstBlock + blockOffset;
                            try {
                                byte[] blockData = mifare.readBlock(blockNumber);
                                // Collect ALL bytes for raw emulation data
                                for (byte b : blockData) {
                                    allRawBytes.add(b);
                                }
                            } catch (IOException e) {
                                Log.e(TAG, "Error reading block " + blockNumber + " for raw data: " + e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        Log.i(TAG, "Error reading sector " + sectorIndex + " for raw data: " + e.getMessage());
                    }
                }

                mifare.close();
                
                // Convert raw bytes to hex string for emulation
                if (allRawBytes.size() > 0) {
                    byte[] rawBytesArray = new byte[allRawBytes.size()];
                    for (int i = 0; i < allRawBytes.size(); i++) {
                        rawBytesArray[i] = allRawBytes.get(i);
                    }
                    rawMifareData = bytesToHex(rawBytesArray);
                    Log.i(TAG, "Raw MIFARE data collected: " + rawMifareData.length() + " hex chars - Success");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading raw MIFARE data: " + e.getMessage(), e);
                try {
                    if (mifare != null && mifare.isConnected()) {
                        mifare.close();
                    }
                } catch (IOException closeException) {
                    Log.e(TAG, "Error closing MIFARE connection: " + closeException.getMessage());
                }
            }
        }

        // STEP 2: Read NDEF records (if available) for logical card data
        Ndef ndef = Ndef.get(tag);
        if (ndef != null) {
            try {
                ndef.connect();
                Log.i(TAG, "NDEF detected, reading NDEF records - Reading");
                
                NdefMessage ndefMessage = ndef.getNdefMessage();
                if (ndefMessage != null) {
                    NdefRecord[] records = ndefMessage.getRecords();
                    Log.i(TAG, "Found " + records.length + " NDEF records - Reading");
                    
                    List<String> textRecords = new ArrayList<>();
                    StringBuilder allData = new StringBuilder();
                    
                    for (NdefRecord record : records) {
                        if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN) {
                            byte[] type = record.getType();
                            if (java.util.Arrays.equals(type, NdefRecord.RTD_TEXT)) {
                                String text = parseTextRecord(record);
                                if (text != null && !text.isEmpty()) {
                                    textRecords.add(text);
                                    allData.append(text);
                                    Log.i(TAG, "Found text record: " + text.substring(0, Math.min(50, text.length())) + "... - Reading");
                                }
                            }
                        }
                        
                        // Also try to read raw payload as string (might contain JSON)
                        byte[] payload = record.getPayload();
                        if (payload != null && payload.length > 0) {
                            try {
                                String payloadStr = new String(payload, StandardCharsets.UTF_8);
                                if (payloadStr.trim().length() > 0) {
                                    allData.append(payloadStr);
                                    Log.i(TAG, "Found payload data: " + payloadStr.substring(0, Math.min(50, payloadStr.length())) + "... - Reading");
                                }
                            } catch (Exception e) {
                                // Ignore encoding errors
                            }
                        }
                    }
                    
                    if (allData.length() > 0) {
                        data = allData.toString();
                        // Store NDEF message bytes as rawData (for NDEF-based cards)
                        rawData = bytesToHex(ndefMessage.toByteArray());
                        Log.i(TAG, "NDEF data extracted: " + data.substring(0, Math.min(100, data.length())) + "... - Success");
                    }
                    
                    ndef.close();
                } else {
                    Log.i(TAG, "No NDEF message found - Reading");
                    ndef.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading NDEF: " + e.getMessage(), e);
                try {
                    if (ndef.isConnected()) {
                        ndef.close();
                    }
                } catch (IOException closeException) {
                    Log.e(TAG, "Error closing NDEF: " + closeException.getMessage());
                }
            }
        }

        // STEP 3: If we have raw MIFARE data, use it for emulation (preferred)
        // If we only have NDEF rawData, use that
        if (!rawMifareData.isEmpty()) {
            // Use raw MIFARE Classic data for emulation (most accurate)
            rawData = rawMifareData;
            Log.i(TAG, "Using raw MIFARE Classic data for emulation: " + rawData.length() + " hex chars");
        } else if (rawData.isEmpty() && !rawMifareData.isEmpty()) {
            // Fallback: use MIFARE data if NDEF didn't provide rawData
            rawData = rawMifareData;
        }
        
        // If we still don't have logical data and have raw MIFARE data, try to extract JSON from it
        if (data.isEmpty() && !rawMifareData.isEmpty()) {
            try {
                // Convert hex back to bytes to parse
                byte[] rawBytes = hexStringToBytes(rawMifareData);
                String fullData = new String(rawBytes, StandardCharsets.UTF_8);
                fullData = fullData.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
                
                // Look for JSON
                int jsonStart = fullData.indexOf("{");
                int jsonEnd = fullData.lastIndexOf("}");
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    data = fullData.substring(jsonStart, jsonEnd + 1);
                    Log.i(TAG, "Extracted JSON from raw MIFARE data: " + data.substring(0, Math.min(100, data.length())) + "... - Success");
                }
            } catch (Exception e) {
                Log.i(TAG, "Could not extract JSON from raw MIFARE data: " + e.getMessage());
            }
        }

        // Send event to JavaScript
        long timestamp = System.currentTimeMillis();
        Log.i(TAG, "Sending onCardScanned event - Success");
        if (listener != null) {
            listener.onCardScanned(uid, data, rawData, timestamp);
        }
    }

    private String parseTextRecord(NdefRecord record) {
        try {
            byte[] payload = record.getPayload();
            if (payload == null || payload.length == 0) {
                return null;
            }
            
            // First byte contains language code length and encoding
            boolean isUtf16 = (payload[0] & 0x80) != 0;
            int languageCodeLength = payload[0] & 0x3F;
            java.nio.charset.Charset textEncoding = isUtf16 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8;
            
            // Extract text
            if (payload.length > languageCodeLength + 1) {
                byte[] textBytes = new byte[payload.length - languageCodeLength - 1];
                System.arraycopy(payload, languageCodeLength + 1, textBytes, 0, textBytes.length);
                return new String(textBytes, textEncoding);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing text record: " + e.getMessage(), e);
        }
        return null;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * Convert hex string to byte array.
     */
    private byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    public void updateActivity(Activity activity) {
        this.currentActivity = activity;
        Log.i(TAG, "Activity updated");
    }

    /**
     * Start Host Card Emulation (HCE) with the provided card data.
     * @param uid The card UID (hex string)
     * @param data The card data (JSON string or raw data)
     */
    public void startCardEmulation(String uid, String data) {
        Log.i(TAG, "Starting card emulation - UID: " + uid);
        MifareCardEmulationService.setCardData(uid, data);
        Log.i(TAG, "Card emulation started successfully");
    }

    /**
     * Stop Host Card Emulation (HCE).
     */
    public void stopCardEmulation() {
        Log.i(TAG, "Stopping card emulation");
        MifareCardEmulationService.clearCardData();
        Log.i(TAG, "Card emulation stopped");
    }

    /**
     * Check if card emulation is active.
     * @return true if card data is set, false otherwise
     */
    public boolean isCardEmulationActive() {
        String uid = MifareCardEmulationService.getCardUid();
        boolean active = uid != null && !uid.isEmpty();
        Log.i(TAG, "Card emulation active: " + active);
        return active;
    }
}
