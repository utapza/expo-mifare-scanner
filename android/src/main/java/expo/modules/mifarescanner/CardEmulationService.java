package expo.modules.mifarescanner;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

/**
 * Host Card Emulation (HCE) service for emulating NFC Forum Type 4 Tag with NDEF content.
 * This service allows the phone to act as an NFC Type 4 tag when tapped on a reader.
 * Changed for Type 4 NDEF support: Now emulates ISO-14443-4 / ISO 7816-4 compliant tag.
 */
public class CardEmulationService extends HostApduService {
    private static final String TAG = "CardEmulation";
    
    // Static initializer to verify class is loaded
    static {
        Log.i(TAG, "========================================");
        Log.i(TAG, "CardEmulationService class loaded!");
        Log.i(TAG, "Type 4 NDEF Tag Emulation Mode");
        Log.i(TAG, "========================================");
    }
    
    // Changed for Type 4 NDEF support: NFC Forum Type 4 Tag Application AID
    private static final byte[] SELECT_APDU = {
        (byte) 0x00, (byte) 0xA4, (byte) 0x04, (byte) 0x00, (byte) 0x07,
        (byte) 0xD2, (byte) 0x76, (byte) 0x00, (byte) 0x00, (byte) 0x85, (byte) 0x01, (byte) 0x01
    };
    
    // APDU response constants
    private static final byte[] SELECT_OK = {(byte) 0x90, (byte) 0x00};
    private static final byte[] FILE_NOT_FOUND = {(byte) 0x6A, (byte) 0x82};
    private static final byte[] WRONG_PARAMETERS = {(byte) 0x6B, (byte) 0x00};
    
    // Changed for Type 4 NDEF support: File IDs for Type 4 Tag structure
    private static final byte[] FILE_ID_CC = {(byte) 0xE1, (byte) 0x03}; // Capability Container file
    private static final byte[] FILE_ID_NDEF = {(byte) 0xE1, (byte) 0x04}; // NDEF file
    
    // Changed for Type 4 NDEF support: Current selected file state
    private static volatile int selectedFile = -1; // -1 = none, 0 = CC, 1 = NDEF
    
    // Changed for Type 4 NDEF support: Store CC and NDEF file separately
    private static volatile byte[] ccFile = null; // Capability Container (15 bytes)
    private static volatile byte[] ndefFile = null; // NDEF file (NLEN + NDEF message)
    private static volatile String cardUid = null;
    private static final Object dataLock = new Object();
    
    /**
     * Set the card data to emulate.
     * Changed for Type 4 NDEF support: Now creates NDEF message and CC file.
     * @param uid The card UID (hex string) - Note: Android HCE cannot set custom UID
     * @param data The card data - can be:
     *             - Hex string (raw NDEF message bytes) - preferred if already encoded
     *             - JSON string (will be wrapped as NDEF Text record with language "en", UTF-8)
     *             - Plain text (will be wrapped as NDEF Text record)
     */
    public static void setCardData(String uid, String data) {
        Log.i(TAG, "========================================");
        Log.i(TAG, "CardEmulationService.setCardData() CALLED");
        Log.i(TAG, "UID: " + uid + ", Data length: " + (data != null ? data.length() : 0));
        Log.i(TAG, "Type 4 NDEF Tag Emulation Mode");
        Log.i(TAG, "========================================");
        synchronized (dataLock) {
            cardUid = uid;
            selectedFile = -1; // Reset selected file
            
            if (data != null && !data.isEmpty()) {
                try {
                    byte[] ndefMessageBytes;
                    
                    // Changed for Type 4 NDEF support: Check if data is hex (already NDEF encoded)
                    if (isHexString(data)) {
                        // Assume hex string is already a complete NDEF message
                        ndefMessageBytes = hexStringToBytes(data);
                        Log.i(TAG, "Card data set (hex/raw NDEF) - UID: " + uid + ", NDEF length: " + ndefMessageBytes.length + " bytes");
                        Log.d(TAG, "First 32 bytes (hex): " + bytesToHexStatic(Arrays.copyOf(ndefMessageBytes, Math.min(32, ndefMessageBytes.length))));
                    } else {
                        // Changed for Type 4 NDEF support: JSON/Text string - wrap as NDEF Text record
                        Log.i(TAG, "Card data set (JSON/Text) - wrapping as NDEF Text record");
                        Log.d(TAG, "Data preview: " + data.substring(0, Math.min(100, data.length())));
                        
                        // Create NDEF Text record: TNF=1 (Well Known), Type="T" (Text), language="en", UTF-8
                        ndefMessageBytes = createNdefTextRecord(data, "en");
                        Log.i(TAG, "Created NDEF Text record - length: " + ndefMessageBytes.length + " bytes");
                    }
                    
                    // Changed for Type 4 NDEF support: Create Capability Container (CC) file
                    // CC file format: [00 0F 20 00 00 00 00 00 00 00 00 00 00 00 00]
                    // - Byte 0: Magic number (0x00)
                    // - Bytes 1-2: Version (0x000F = version 1.0, max NDEF file size 0x0FFF = 4095 bytes)
                    // - Byte 3: Max read binary length (0x20 = 32 bytes)
                    // - Bytes 4-14: Reserved (0x00)
                    int ndefFileSize = 2 + ndefMessageBytes.length; // NLEN (2 bytes) + NDEF message
                    ccFile = createCapabilityContainer(ndefFileSize);
                    
                    // Changed for Type 4 NDEF support: Create NDEF file (NLEN + NDEF message)
                    // NLEN is 2-byte big-endian length of NDEF message
                    ndefFile = new byte[2 + ndefMessageBytes.length];
                    ndefFile[0] = (byte) ((ndefMessageBytes.length >> 8) & 0xFF);
                    ndefFile[1] = (byte) (ndefMessageBytes.length & 0xFF);
                    System.arraycopy(ndefMessageBytes, 0, ndefFile, 2, ndefMessageBytes.length);
                    
                    Log.i(TAG, "CC file created: " + ccFile.length + " bytes");
                    Log.i(TAG, "NDEF file created: " + ndefFile.length + " bytes (NLEN: " + ndefMessageBytes.length + ", NDEF: " + ndefMessageBytes.length + ")");
                    Log.i(TAG, "Card emulation data is now ready - service will respond to Type 4 APDU commands");
                } catch (Exception e) {
                    Log.e(TAG, "Error creating Type 4 tag data: " + e.getMessage(), e);
                    ccFile = null;
                    ndefFile = null;
                }
            } else {
                Log.w(TAG, "Card data set - UID: " + uid + ", Data is empty - emulation will not work");
                ccFile = null;
                ndefFile = null;
            }
        }
    }
    
    /**
     * Changed for Type 4 NDEF support: Create Capability Container file (15 bytes).
     * @param ndefFileSize Total size of NDEF file (NLEN + NDEF message)
     * @return CC file bytes
     */
    private static byte[] createCapabilityContainer(int ndefFileSize) {
        byte[] cc = new byte[15];
        cc[0] = 0x00; // Magic number
        cc[1] = 0x0F; // Version 1.0, max NDEF file size high byte (0x0FFF = 4095 bytes max)
        cc[2] = (byte) 0xFF; // Max NDEF file size low byte
        cc[3] = 0x20; // Max read binary length (32 bytes)
        // Bytes 4-14 are reserved (already 0x00)
        return cc;
    }
    
    /**
     * Changed for Type 4 NDEF support: Create NDEF Text record.
     * Format: [MB|ME|CF|SR|IL|TNF(3)] [Type Length] [Payload Length (1 or 4)] [ID Length (if IL=1)] [Type] [ID (if IL=1)] [Payload]
     * For Text record: TNF=1 (Well Known), Type="T" (0x54), Payload = [Status Byte][Language Code][Text]
     * @param text The text content (JSON string or plain text)
     * @param languageCode Language code (e.g., "en")
     * @return NDEF message bytes (single record)
     */
    private static byte[] createNdefTextRecord(String text, String languageCode) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] langBytes = languageCode.getBytes(StandardCharsets.US_ASCII);
        
        // Calculate total payload: 1 byte (status) + language code + text
        int payloadLength = 1 + langBytes.length + textBytes.length;
        
        // NDEF record header:
        // Byte 0: Flags (MB=1, ME=1, CF=0, SR=1, IL=0, TNF=001)
        // MB=Message Begin, ME=Message End, SR=Short Record (< 256 bytes), IL=No ID Length, TNF=Well Known
        byte flags = (byte) 0xD1; // 1101 0001 = MB=1, ME=1, SR=1, TNF=1
        
        // Type Length: 1 byte (for "T")
        byte typeLength = 0x01;
        
        // Payload Length: 1 byte (SR=1, so 1 byte length)
        byte payloadLengthByte = (byte) (payloadLength & 0xFF);
        
        // Type: "T" (0x54)
        byte[] type = {(byte) 0x54};
        
        // Payload: [Status Byte][Language Code][Text]
        // Status byte: bit 7 = UTF-8 (0), bits 6-0 = language code length
        byte statusByte = (byte) langBytes.length;
        byte[] payload = new byte[payloadLength];
        payload[0] = statusByte;
        System.arraycopy(langBytes, 0, payload, 1, langBytes.length);
        System.arraycopy(textBytes, 0, payload, 1 + langBytes.length, textBytes.length);
        
        // Build complete NDEF record
        int totalLength = 1 + 1 + 1 + type.length + payload.length; // flags + typeLen + payloadLen + type + payload
        byte[] ndefRecord = new byte[totalLength];
        int offset = 0;
        
        ndefRecord[offset++] = flags;
        ndefRecord[offset++] = typeLength;
        ndefRecord[offset++] = payloadLengthByte;
        System.arraycopy(type, 0, ndefRecord, offset, type.length);
        offset += type.length;
        System.arraycopy(payload, 0, ndefRecord, offset, payload.length);
        
        return ndefRecord;
    }
    
    /**
     * Check if a string is a hex string (contains only hex characters and is even length).
     */
    private static boolean isHexString(String str) {
        if (str == null || str.length() < 2 || str.length() % 2 != 0) {
            return false;
        }
        // Check if all characters are hex (0-9, a-f, A-F)
        for (char c : str.toCharArray()) {
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        // If it's a long hex string (likely raw NDEF data), it's probably hex
        // If it's short and looks like JSON, it's probably not hex
        if (str.length() > 64 && str.length() % 2 == 0) {
            return true; // Long hex string, likely raw NDEF data
        }
        // For shorter strings, check if it starts with '{' (JSON) or contains spaces (text)
        if (str.trim().startsWith("{") || str.contains(" ")) {
            return false; // Likely JSON or text
        }
        return true; // Default to hex if it passes the character check
    }
    
    /**
     * Clear the card data.
     * Changed for Type 4 NDEF support: Clear CC and NDEF files.
     */
    public static void clearCardData() {
        synchronized (dataLock) {
            cardUid = null;
            ccFile = null;
            ndefFile = null;
            selectedFile = -1;
            Log.i(TAG, "Card data cleared (CC and NDEF files)");
        }
    }
    
    /**
     * Get the current card UID.
     */
    public static String getCardUid() {
        synchronized (dataLock) {
            return cardUid;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "CardEmulationService onCreate() called - Service is being created by Android");
        Log.i(TAG, "CardEmulationService is now active and ready to handle Type 4 APDU commands");
    }
    
    @Override
    public void onDeactivated(int reason) {
        String reasonStr = "UNKNOWN";
        switch (reason) {
            case DEACTIVATION_LINK_LOSS:
                reasonStr = "LINK_LOSS";
                break;
            case DEACTIVATION_DESELECTED:
                reasonStr = "DESELECTED";
                break;
        }
        Log.i(TAG, "CardEmulationService onDeactivated() called - reason: " + reasonStr + " (" + reason + ")");
        // Changed for Type 4 NDEF support: Reset selected file on deactivation
        synchronized (dataLock) {
            selectedFile = -1;
        }
    }
    
    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        if (commandApdu == null || commandApdu.length == 0) {
            Log.w(TAG, "Received null or empty APDU command");
            return WRONG_PARAMETERS;
        }
        
        Log.d(TAG, "Received APDU command: " + bytesToHexStatic(commandApdu) + " (length: " + commandApdu.length + ")");
        
        // Check if card data is available
        synchronized (dataLock) {
            if (ccFile == null || ndefFile == null || cardUid == null) {
                Log.w(TAG, "No card data available for emulation");
                // Still respond to SELECT AID to indicate service is available
                if (commandApdu.length >= 5 && commandApdu[0] == (byte) 0x00 && commandApdu[1] == (byte) 0xA4) {
                    return SELECT_OK;
                }
                return WRONG_PARAMETERS;
            }
        }
        
        // Changed for Type 4 NDEF support: Handle SELECT AID command (Application Selection)
        if (commandApdu.length >= 5 && commandApdu[0] == (byte) 0x00 && commandApdu[1] == (byte) 0xA4) {
            // Check if this is SELECT AID (P1=04) or SELECT FILE (P1=00 or 0C)
            byte p1 = commandApdu[2];
            
            if (p1 == 0x04) {
                // SELECT AID - select application
                Log.i(TAG, "SELECT AID command received");
                synchronized (dataLock) {
                    selectedFile = -1; // Reset selected file
                }
                return SELECT_OK;
            } else if (p1 == 0x00 || p1 == 0x0C) {
                // Changed for Type 4 NDEF support: SELECT FILE command
                // Format: CLA=00, INS=A4, P1=00/0C, P2=00, Lc=02, File ID (2 bytes)
                if (commandApdu.length >= 7) {
                    byte[] fileId = new byte[2];
                    fileId[0] = commandApdu[5];
                    fileId[1] = commandApdu[6];
                    
                    synchronized (dataLock) {
                        if (Arrays.equals(fileId, FILE_ID_CC)) {
                            selectedFile = 0; // CC file selected
                            Log.i(TAG, "SELECT FILE: CC file (E103) selected");
                            return SELECT_OK;
                        } else if (Arrays.equals(fileId, FILE_ID_NDEF)) {
                            selectedFile = 1; // NDEF file selected
                            Log.i(TAG, "SELECT FILE: NDEF file (E104) selected");
                            return SELECT_OK;
                        } else {
                            Log.w(TAG, "SELECT FILE: Unknown file ID: " + bytesToHexStatic(fileId));
                            selectedFile = -1;
                            return FILE_NOT_FOUND;
                        }
                    }
                } else {
                    Log.w(TAG, "SELECT FILE: Invalid command length");
                    return WRONG_PARAMETERS;
                }
            }
        }
        
        // Changed for Type 4 NDEF support: Handle READ BINARY command (00 B0)
        // Format: CLA=00, INS=B0, P1=high byte of offset, P2=low byte of offset, Le=length
        if (commandApdu.length >= 5 && commandApdu[0] == (byte) 0x00 && commandApdu[1] == (byte) 0xB0) {
            synchronized (dataLock) {
                byte[] targetFile = null;
                String fileType = "";
                
                // Determine which file to read based on selected file or offset
                int offset = ((commandApdu[2] & 0xFF) << 8) | (commandApdu[3] & 0xFF);
                int length = commandApdu.length > 4 ? (commandApdu[4] & 0xFF) : 0;
                if (length == 0) length = 256; // 0x00 means 256 bytes
                
                // Changed for Type 4 NDEF support: Check selected file or infer from offset
                if (selectedFile == 0) {
                    // CC file selected
                    targetFile = ccFile;
                    fileType = "CC";
                } else if (selectedFile == 1) {
                    // NDEF file selected
                    targetFile = ndefFile;
                    fileType = "NDEF";
                } else {
                    // No file selected - try to infer from offset (CC is at 0x0000, NDEF typically after CC)
                    // CC file is 15 bytes, so offset < 15 is CC, otherwise NDEF
                    if (offset < 15) {
                        targetFile = ccFile;
                        fileType = "CC (inferred)";
                    } else {
                        targetFile = ndefFile;
                        fileType = "NDEF (inferred)";
                        offset -= 15; // Adjust offset for NDEF file
                    }
                }
                
                if (targetFile != null && targetFile.length > 0) {
                    // Ensure we don't read beyond available data
                    int actualLength = Math.min(length, targetFile.length - offset);
                    if (actualLength <= 0 || offset >= targetFile.length) {
                        Log.w(TAG, "READ BINARY: Invalid offset/length for " + fileType + " file (offset: " + offset + ", length: " + length + ", file size: " + targetFile.length + ")");
                        return WRONG_PARAMETERS;
                    }
                    
                    // Return requested data
                    byte[] response = new byte[actualLength + 2];
                    System.arraycopy(targetFile, offset, response, 0, actualLength);
                    response[actualLength] = (byte) 0x90;
                    response[actualLength + 1] = (byte) 0x00;
                    Log.i(TAG, "READ BINARY: Returning " + actualLength + " bytes from " + fileType + " file (offset: " + offset + ")");
                    return response;
                } else {
                    Log.w(TAG, "READ BINARY: No file data available");
                    return WRONG_PARAMETERS;
                }
            }
        }
        
        // Changed for Type 4 NDEF support: Removed MIFARE Classic 0x30 READ handler (not needed for Type 4)
        
        // Handle GET DATA command - return UID if requested (optional, for compatibility)
        if (commandApdu.length >= 5 && commandApdu[0] == (byte) 0x00 && commandApdu[1] == (byte) 0xCA) {
            Log.i(TAG, "GET DATA command received, returning UID");
            synchronized (dataLock) {
                if (cardUid != null) {
                    try {
                        byte[] uidBytes = hexStringToBytes(cardUid);
                        byte[] response = new byte[uidBytes.length + 2];
                        System.arraycopy(uidBytes, 0, response, 0, uidBytes.length);
                        response[uidBytes.length] = (byte) 0x90;
                        response[uidBytes.length + 1] = (byte) 0x00;
                        return response;
                    } catch (Exception e) {
                        Log.e(TAG, "Error converting UID to bytes: " + e.getMessage());
                    }
                }
            }
        }
        
        // Changed for Type 4 NDEF support: Handle unknown commands gracefully
        Log.d(TAG, "Unknown command, returning FILE_NOT_FOUND for compatibility");
        return FILE_NOT_FOUND;
    }
    
    /**
     * Convert hex string to byte array.
     * Static method to allow calling from static context.
     */
    private static byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
    
    /**
     * Convert byte array to hex string for logging (instance method).
     */
    private String bytesToHex(byte[] bytes) {
        return bytesToHexStatic(bytes);
    }
    
    /**
     * Convert byte array to hex string for logging (static helper).
     */
    private static String bytesToHexStatic(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
