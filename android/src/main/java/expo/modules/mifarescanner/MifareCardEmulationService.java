package expo.modules.mifarescanner;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;
import java.util.Arrays;

/**
 * Host Card Emulation (HCE) service for emulating MIFARE Classic NFC cards.
 * This service allows the phone to act as an NFC card when tapped on a reader.
 */
public class MifareCardEmulationService extends HostApduService {
    private static final String TAG = "MifareCardEmulation";
    
    // APDU command constants
    private static final byte[] SELECT_APDU = {
        (byte) 0x00, (byte) 0xA4, (byte) 0x04, (byte) 0x00, (byte) 0x07,
        (byte) 0xF0, (byte) 0x39, (byte) 0x41, (byte) 0x48, (byte) 0x14, (byte) 0x81, (byte) 0x00
    };
    
    private static final byte[] SELECT_OK = {(byte) 0x90, (byte) 0x00};
    private static final byte[] UNKNOWN_CMD = {(byte) 0x00, (byte) 0x00};
    
    // Card data storage (set from native module)
    private static volatile byte[] cardData = null;
    private static volatile String cardUid = null;
    private static final Object dataLock = new Object();
    
    /**
     * Set the card data to emulate.
     * @param uid The card UID (hex string)
     * @param data The card data - can be:
     *             - Hex string (raw MIFARE data) - preferred for emulation
     *             - JSON string (logical card data)
     *             - Plain text
     */
    public static void setCardData(String uid, String data) {
        synchronized (dataLock) {
            cardUid = uid;
            if (data != null && !data.isEmpty()) {
                try {
                    // Check if data is a hex string (raw MIFARE data)
                    // Hex strings are typically longer and contain only hex characters
                    if (isHexString(data)) {
                        // Convert hex string to bytes (raw MIFARE data)
                        cardData = hexStringToBytes(data);
                        Log.i(TAG, "Card data set (hex/raw) - UID: " + uid + ", Data length: " + cardData.length + " bytes");
                    } else {
                        // Convert string data to bytes (JSON or text)
                        cardData = data.getBytes("UTF-8");
                        Log.i(TAG, "Card data set (text/JSON) - UID: " + uid + ", Data length: " + cardData.length + " bytes");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error converting card data to bytes: " + e.getMessage());
                    cardData = null;
                }
            } else {
                cardData = null;
            }
        }
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
        // If it's a long hex string (likely raw data), it's probably hex
        // If it's short and looks like JSON, it's probably not hex
        if (str.length() > 64 && str.length() % 2 == 0) {
            return true; // Long hex string, likely raw data
        }
        // For shorter strings, check if it starts with '{' (JSON) or contains spaces (text)
        if (str.trim().startsWith("{") || str.contains(" ")) {
            return false; // Likely JSON or text
        }
        return true; // Default to hex if it passes the character check
    }
    
    /**
     * Clear the card data.
     */
    public static void clearCardData() {
        synchronized (dataLock) {
            cardUid = null;
            cardData = null;
            Log.i(TAG, "Card data cleared");
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
        Log.i(TAG, "MifareCardEmulationService created");
    }
    
    @Override
    public void onDeactivated(int reason) {
        Log.i(TAG, "MifareCardEmulationService deactivated, reason: " + reason);
    }
    
    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        if (commandApdu == null || commandApdu.length == 0) {
            Log.w(TAG, "Received null or empty APDU command");
            return UNKNOWN_CMD;
        }
        
        Log.d(TAG, "Received APDU command: " + bytesToHex(commandApdu) + " (length: " + commandApdu.length + ")");
        
        // Check if card data is available
        synchronized (dataLock) {
            if (cardData == null || cardUid == null) {
                Log.w(TAG, "No card data available for emulation");
                // Still respond to SELECT to indicate service is available
                if (commandApdu.length >= 5 && commandApdu[0] == (byte) 0x00 && commandApdu[1] == (byte) 0xA4) {
                    return SELECT_OK;
                }
                return UNKNOWN_CMD;
            }
        }
        
        // Handle SELECT command (Application Selection)
        if (commandApdu.length >= 5 && commandApdu[0] == (byte) 0x00 && commandApdu[1] == (byte) 0xA4) {
            Log.i(TAG, "SELECT command received");
            return SELECT_OK;
        }
        
        // Handle READ BINARY command (0xB0) - ISO 7816-4
        if (commandApdu.length >= 5 && commandApdu[0] == (byte) 0x00 && commandApdu[1] == (byte) 0xB0) {
            Log.i(TAG, "READ BINARY command received, returning card data");
            synchronized (dataLock) {
                if (cardData != null && cardData.length > 0) {
                    // Calculate offset and length from P1 and P2
                    int offset = ((commandApdu[2] & 0xFF) << 8) | (commandApdu[3] & 0xFF);
                    int length = commandApdu[4] & 0xFF;
                    if (length == 0) length = 256; // 0x00 means 256 bytes
                    
                    // Ensure we don't read beyond available data
                    int actualLength = Math.min(length, cardData.length - offset);
                    if (actualLength <= 0 || offset >= cardData.length) {
                        return new byte[]{(byte) 0x6B, (byte) 0x00}; // Wrong parameters
                    }
                    
                    // Return requested data
                    byte[] response = new byte[actualLength + 2];
                    System.arraycopy(cardData, offset, response, 0, actualLength);
                    response[actualLength] = (byte) 0x90;
                    response[actualLength + 1] = (byte) 0x00;
                    return response;
                }
            }
        }
        
        // Handle READ command (0x30) - MIFARE Classic style
        if (commandApdu.length >= 2 && commandApdu[0] == (byte) 0x30) {
            Log.i(TAG, "READ command (0x30) received, returning card data");
            synchronized (dataLock) {
                if (cardData != null && cardData.length > 0) {
                    // Return card data with success status
                    // Limit response size to avoid issues
                    int maxLength = Math.min(cardData.length, 254); // Leave room for status bytes
                    byte[] response = new byte[maxLength + 2];
                    System.arraycopy(cardData, 0, response, 0, maxLength);
                    response[maxLength] = (byte) 0x90;
                    response[maxLength + 1] = (byte) 0x00;
                    return response;
                }
            }
        }
        
        // Handle GET DATA command - return UID if requested
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
        
        // Handle other commands - return success for compatibility
        Log.d(TAG, "Unknown command, returning success for compatibility");
        return SELECT_OK;
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
     * Convert byte array to hex string for logging.
     */
    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
