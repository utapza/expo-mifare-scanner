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

        // First, try to read NDEF records (if card supports NDEF)
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

        // If NDEF didn't work or returned empty, try MIFARE Classic direct reading
        if (data.isEmpty()) {
            MifareClassic mifare = null;
            try {
                mifare = MifareClassic.get(tag);
                if (mifare != null) {
                    Log.i(TAG, "MIFARE Classic detected, type: " + mifare.getType() + " - Discovery");
                    
                    try {
                        mifare.connect();
                        Log.i(TAG, "Connected to MIFARE card - Discovery");

                        int sectorCount = mifare.getSectorCount();
                        Log.i(TAG, "Card has " + sectorCount + " sectors - Reading");
                        
                        StringBuilder allBlocksData = new StringBuilder();
                        List<Byte> allBytes = new ArrayList<>();
                        
                        // Try to read multiple sectors
                        for (int sectorIndex = 0; sectorIndex < Math.min(sectorCount, 16); sectorIndex++) {
                            try {
                                Log.i(TAG, "Attempting authentication on sector " + sectorIndex + " - Authentication");
                                
                                // Try default key first
                                boolean authenticated = mifare.authenticateSectorWithKeyA(
                                    sectorIndex,
                                    MifareClassic.KEY_DEFAULT
                                );
                                
                                if (!authenticated) {
                                    // Try key B
                                    authenticated = mifare.authenticateSectorWithKeyB(
                                        sectorIndex,
                                        MifareClassic.KEY_DEFAULT
                                    );
                                }
                                
                                if (!authenticated) {
                                    Log.i(TAG, "Failed to authenticate sector " + sectorIndex + " - Authentication failed");
                                    continue;
                                }

                                Log.i(TAG, "Successfully authenticated sector " + sectorIndex + " - Authentication");

                                int firstBlock = mifare.sectorToBlock(sectorIndex);
                                int blockCount = mifare.getBlockCountInSector(sectorIndex);
                                int lastBlock = firstBlock + blockCount - 1; // Sector trailer
                                
                                Log.i(TAG, "Sector " + sectorIndex + ": blocks " + firstBlock + " to " + lastBlock + " (trailer at " + lastBlock + ") - Reading");
                                
                                // Read all blocks in this sector (except the last one which is the sector trailer)
                                for (int blockOffset = 0; blockOffset < blockCount - 1; blockOffset++) {
                                    int blockNumber = firstBlock + blockOffset;
                                    try {
                                        byte[] blockData = mifare.readBlock(blockNumber);
                                        String blockHex = bytesToHex(blockData);
                                        
                                        Log.i(TAG, "Block " + blockNumber + " hex: " + blockHex.substring(0, Math.min(32, blockHex.length())) + "... - Reading");
                                        
                                        // Check if block is all zeros or all same byte (likely empty)
                                        boolean isEmpty = true;
                                        byte firstByte = blockData[0];
                                        for (byte b : blockData) {
                                            if (b != 0 && b != firstByte) {
                                                isEmpty = false;
                                                break;
                                            }
                                        }
                                        
                                        if (isEmpty) {
                                            Log.i(TAG, "Block " + blockNumber + " appears empty, skipping - Reading");
                                            continue;
                                        }
                                        
                                        // Collect bytes
                                        for (byte b : blockData) {
                                            allBytes.add(b);
                                        }
                                        
                                        // Try to read as string, but be more careful
                                        String blockStr = new String(blockData, StandardCharsets.UTF_8);
                                        // Remove null characters and control characters
                                        blockStr = blockStr.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
                                        
                                        // Check if it looks like readable text (has printable ASCII or JSON-like characters)
                                        boolean hasReadableText = false;
                                        int printableCount = 0;
                                        for (char c : blockStr.toCharArray()) {
                                            if (c >= 32 && c < 127) { // Printable ASCII
                                                printableCount++;
                                            }
                                        }
                                        
                                        // If at least 50% is printable, consider it readable
                                        if (blockStr.length() > 0 && printableCount > blockStr.length() / 2) {
                                            hasReadableText = true;
                                        }
                                        
                                        if (hasReadableText) {
                                            allBlocksData.append(blockStr);
                                            Log.i(TAG, "Block " + blockNumber + " readable text: " + blockStr.substring(0, Math.min(50, blockStr.length())) + "... - Reading");
                                        } else {
                                            Log.i(TAG, "Block " + blockNumber + " doesn't contain readable text (printable: " + printableCount + "/" + blockStr.length() + ") - Reading");
                                        }
                                        
                                    } catch (IOException e) {
                                        Log.e(TAG, "Error reading block " + blockNumber + ": " + e.getMessage());
                                    }
                                }
                                
                            } catch (Exception e) {
                                Log.i(TAG, "Error processing sector " + sectorIndex + ": " + e.getMessage());
                            }
                        }

                        mifare.close();
                        Log.i(TAG, "MIFARE connection closed");

                        // Convert collected bytes to hex
                        byte[] allBytesArray = new byte[allBytes.size()];
                        for (int i = 0; i < allBytes.size(); i++) {
                            allBytesArray[i] = allBytes.get(i);
                        }
                        rawData = bytesToHex(allBytesArray);
                        
                        // Use the string data if we found any
                        if (allBlocksData.length() > 0) {
                            data = allBlocksData.toString().trim();
                            // Try to find JSON in the data
                            int jsonStart = data.indexOf("{");
                            int jsonEnd = data.lastIndexOf("}");
                            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                                data = data.substring(jsonStart, jsonEnd + 1);
                                Log.i(TAG, "Found JSON in data: " + data.substring(0, Math.min(100, data.length())) + "... - Success");
                            }
                        } else if (allBytesArray.length > 0) {
                            // Try to parse as JSON or text
                            String fullData = new String(allBytesArray, StandardCharsets.UTF_8);
                            fullData = fullData.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
                            
                            // Look for JSON
                            int jsonStart = fullData.indexOf("{");
                            int jsonEnd = fullData.lastIndexOf("}");
                            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                                data = fullData.substring(jsonStart, jsonEnd + 1);
                                Log.i(TAG, "Found JSON in raw bytes: " + data.substring(0, Math.min(100, data.length())) + "... - Success");
                            } else {
                                // Just use the cleaned string
                                data = fullData.trim();
                            }
                        }
                        
                        Log.i(TAG, "MIFARE data extracted, length: " + data.length() + ", rawData length: " + rawData.length() + " - Success");

                    } catch (IOException e) {
                        Log.e(TAG, "Error reading MIFARE card: " + e.getMessage(), e);
                        Log.i(TAG, "Tag handling failed - Failure");
                        try {
                            if (mifare != null && mifare.isConnected()) {
                                mifare.close();
                            }
                        } catch (IOException closeException) {
                            Log.e(TAG, "Error closing MIFARE connection: " + closeException.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling tag: " + e.getMessage(), e);
                Log.i(TAG, "Tag handling failed - Failure");
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

    public void updateActivity(Activity activity) {
        this.currentActivity = activity;
        Log.i(TAG, "Activity updated");
    }
}
