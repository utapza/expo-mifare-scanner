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
    private String pendingWriteData = null;
    private Object writeLock = new Object();
    private boolean writePending = false;

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
                
                // Check if there's a pending write operation
                synchronized (writeLock) {
                    if (writePending && pendingWriteData != null) {
                        Log.i(TAG, "Write operation pending, writing to tag - Writing");
                        try {
                            writeNfcTag(tag, pendingWriteData);
                            Log.i(TAG, "Write completed successfully - Success");
                            synchronized (writeLock) {
                                writePending = false;
                                pendingWriteData = null;
                                writeLock.notifyAll();
                            }
                            stopScanning();
                            return; // Don't read, just write
                        } catch (Exception e) {
                            Log.e(TAG, "Error during write operation: " + e.getMessage(), e);
                            synchronized (writeLock) {
                                writePending = false;
                                pendingWriteData = null;
                                writeLock.notifyAll();
                            }
                            stopScanning();
                            return;
                        }
                    }
                }
                
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

    /**
     * Write data to NFC tag in NDEF format (readable by other NFC readers)
     * @param tag The NFC tag to write to
     * @param data The data string to write (will be written as NDEF text record)
     * @return true if write was successful, false otherwise
     */
    public boolean writeNfcTag(Tag tag, String data) throws Exception {
        if (tag == null) {
            throw new IllegalArgumentException("Tag cannot be null");
        }
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }

        Log.i(TAG, "Attempting to write data to tag - Writing");
        Log.i(TAG, "Data to write: " + data.substring(0, Math.min(100, data.length())) + "...");

        // First, try NDEF format (best for compatibility with other readers)
        Ndef ndef = Ndef.get(tag);
        if (ndef != null) {
            try {
                ndef.connect();
                Log.i(TAG, "NDEF tag detected, writing NDEF record - Writing");

                // Check if tag is writable
                if (!ndef.isWritable()) {
                    ndef.close();
                    throw new IOException("NDEF tag is not writable");
                }

                // Check available space
                int maxSize = ndef.getMaxSize();
                byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
                if (dataBytes.length > maxSize) {
                    ndef.close();
                    throw new IOException("Data too large. Max size: " + maxSize + ", Data size: " + dataBytes.length);
                }

                // Create NDEF text record
                NdefRecord textRecord = NdefRecord.createTextRecord("en", data);
                NdefMessage ndefMessage = new NdefMessage(new NdefRecord[]{textRecord});

                // Write NDEF message
                ndef.writeNdefMessage(ndefMessage);
                Log.i(TAG, "Successfully wrote NDEF message - Success");
                ndef.close();
                return true;

            } catch (Exception e) {
                Log.e(TAG, "Error writing NDEF: " + e.getMessage(), e);
                try {
                    if (ndef.isConnected()) {
                        ndef.close();
                    }
                } catch (IOException closeException) {
                    Log.e(TAG, "Error closing NDEF: " + closeException.getMessage());
                }
                throw new IOException("Failed to write NDEF: " + e.getMessage(), e);
            }
        }

        // If NDEF is not available, try NdefFormatable (for unformatted tags)
        android.nfc.tech.NdefFormatable ndefFormatable = android.nfc.tech.NdefFormatable.get(tag);
        if (ndefFormatable != null) {
            try {
                ndefFormatable.connect();
                Log.i(TAG, "NdefFormatable tag detected, formatting and writing - Writing");

                // Create NDEF text record
                NdefRecord textRecord = NdefRecord.createTextRecord("en", data);
                NdefMessage ndefMessage = new NdefMessage(new NdefRecord[]{textRecord});

                // Format tag and write message
                ndefFormatable.format(ndefMessage);
                Log.i(TAG, "Successfully formatted and wrote NDEF message - Success");
                ndefFormatable.close();
                return true;

            } catch (Exception e) {
                Log.e(TAG, "Error formatting/writing NDEF: " + e.getMessage(), e);
                try {
                    if (ndefFormatable.isConnected()) {
                        ndefFormatable.close();
                    }
                } catch (IOException closeException) {
                    Log.e(TAG, "Error closing NdefFormatable: " + closeException.getMessage());
                }
                throw new IOException("Failed to format/write NDEF: " + e.getMessage(), e);
            }
        }

        // If neither NDEF nor NdefFormatable is available, try MIFARE Classic raw writing
        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare != null) {
            Log.i(TAG, "MIFARE Classic detected, attempting raw write - Writing");
            Log.w(TAG, "Warning: Raw MIFARE write may not be readable by standard NFC readers");
            
            try {
                mifare.connect();
                Log.i(TAG, "Connected to MIFARE card - Writing");

                byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
                
                // Write to first data block (block 4, sector 1)
                // Note: This requires authentication and may overwrite existing data
                int sectorIndex = 1;
                int blockIndex = 4; // First data block in sector 1
                
                // Authenticate sector
                boolean authenticated = mifare.authenticateSectorWithKeyA(sectorIndex, MifareClassic.KEY_DEFAULT);
                if (!authenticated) {
                    authenticated = mifare.authenticateSectorWithKeyB(sectorIndex, MifareClassic.KEY_DEFAULT);
                }
                
                if (!authenticated) {
                    mifare.close();
                    throw new IOException("Failed to authenticate MIFARE sector for writing");
                }

                Log.i(TAG, "Authenticated sector " + sectorIndex + " - Writing");

                // Write data (max 16 bytes per block)
                int bytesWritten = 0;
                int blockNumber = blockIndex;
                for (int i = 0; i < dataBytes.length && bytesWritten < 16; i++) {
                    // We can only write one block (16 bytes) safely
                    if (i == 0) {
                        byte[] blockData = new byte[16];
                        int copyLength = Math.min(16, dataBytes.length);
                        System.arraycopy(dataBytes, 0, blockData, 0, copyLength);
                        // Pad with zeros if needed
                        for (int j = copyLength; j < 16; j++) {
                            blockData[j] = 0;
                        }
                        
                        mifare.writeBlock(blockNumber, blockData);
                        bytesWritten = copyLength;
                        Log.i(TAG, "Wrote " + bytesWritten + " bytes to block " + blockNumber + " - Writing");
                    }
                }

                mifare.close();
                Log.i(TAG, "Successfully wrote to MIFARE card (raw format) - Success");
                Log.w(TAG, "Note: Raw MIFARE data may not be readable by standard NFC readers");
                return true;

            } catch (Exception e) {
                Log.e(TAG, "Error writing to MIFARE: " + e.getMessage(), e);
                try {
                    if (mifare.isConnected()) {
                        mifare.close();
                    }
                } catch (IOException closeException) {
                    Log.e(TAG, "Error closing MIFARE: " + closeException.getMessage());
                }
                throw new IOException("Failed to write to MIFARE: " + e.getMessage(), e);
            }
        }

        // No supported tag type found
        throw new IOException("Tag type not supported for writing. Tag must support NDEF, NdefFormatable, or MIFARE Classic.");
    }

    /**
     * Write data to NFC tag - waits for tag discovery, then writes
     * @param data The data string to write
     * @param timeoutMs Timeout in milliseconds
     * @return true if write was successful
     */
    public boolean writeNfcTag(String data, long timeoutMs) throws Exception {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }

        Log.i(TAG, "Starting write operation, waiting for tag - Writing");
        
        // Start scanning if not already scanning
        boolean wasScanning = isScanning;
        if (!isScanning) {
            startScanning();
        }

        // Wait for tag discovery
        synchronized (writeLock) {
            long startTime = System.currentTimeMillis();
            while (currentTag == null) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= timeoutMs) {
                    throw new IOException("Timeout waiting for tag to write");
                }
                try {
                    writeLock.wait(timeoutMs - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for tag");
                }
            }
        }

        Tag tagToWrite = currentTag;
        synchronized (writeLock) {
            currentTag = null; // Reset for next write
        }

        if (tagToWrite == null) {
            throw new IOException("No tag available for writing");
        }

        try {
            boolean result = writeNfcTag(tagToWrite, data);
            
            // Stop scanning if we started it
            if (!wasScanning) {
                stopScanning();
            }
            
            return result;
        } catch (Exception e) {
            // Stop scanning if we started it
            if (!wasScanning) {
                stopScanning();
            }
            throw e;
        }
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
