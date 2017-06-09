package com.keepassdroid.mycode;

import org.spongycastle.util.encoders.Hex;

import java.util.Arrays;

/**
 * Created by hilde on 29.05.2017.
 */

public class apduCodes {

    public static final byte[] apduSelectApplet = hexToByteArray("00a40400081122334455667788");
    public static final byte[] apduGetFileName = hexToByteArray("80450101");
    public static final byte[] apdudeleteFile = hexToByteArray("80430303");
    public static final byte[] apduGetFileSize = hexToByteArray("80440304");
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static byte[] hexToByteArray(String data) {

        String hexchars = "0123456789abcdef";
        data = data.replaceAll(" ","").toLowerCase();
        if (data == null) {
            return null;
        }

        byte[] hex = new byte[data.length() / 2];

        for (int ii = 0; ii < data.length(); ii += 2)
        {
            int i1 = hexchars.indexOf(data.charAt(ii));
            int i2 = hexchars.indexOf(data.charAt(ii + 1));
            hex[ii/2] = (byte)((i1 << 4) | i2);
        }
        return hex;
    }

    public static String byteToHexString(byte[] dataToConvert) {
        StringBuilder sb = new StringBuilder();
        for (byte b : dataToConvert) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    public static String byteToString(byte[] dataToConvert) {
        StringBuilder sb = new StringBuilder();
        for (byte b : dataToConvert) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public static String getResponseCode(byte[] response) {

        int len = response.length;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02X", response[len - 2]));
        sb.append(String.format("%02X", response[len - 1]));

        return sb.toString();
    }

    public static String getResponseData(byte[] data) {

        byte[] newData = new byte[data.length - 2];

        for(int i = 0; i < (data.length - 2); i++) {
            newData[i] = data[i];
        }

        return byteToString(newData);
    }

    public static boolean getResponseStatus(byte[] data) {

        if(getResponseCode(data).equals("9000")) {
            return true;
        } else {
            return false;
        }
    }

    public static String checkLength(String data) {
        if ((data.length() % 2) != 0) {
            return  "0" + data;
        } else {
            return data;
        }
    }

    public static String StringToHex(int length) {
        String hex = Integer.toHexString(length);

        return checkLength(hex);
    }

    public static boolean checkInput(String data) {

        String hexchars = "0123456789abcdef";
        int len = data.length();

        for(int i = 0; i < len; i++) {
            boolean found = false;
            for(int o = 0; o < hexchars.length(); o++) {

                if (data.charAt(i) == hexchars.charAt(o)) {
                    found = true;
                }
            }

            if (!found) {
                return false;
            }
        }

        return true;
    }

    public static String dataStringToHex(String input) {
        byte[] data = input.getBytes();
        char[] hexChars = new char[data.length * 2];

        for ( int j = 0; j < data.length; j++ ) {
            int v = data[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static String dataHexToString(String hexData) {
        StringBuilder sb = new StringBuilder();
        StringBuilder temp = new StringBuilder();

        for( int i = 0; i < hexData.length()-1; i += 2 ){

            //grab the hex in pairs
            String output = hexData.substring(i, (i + 2));
            //convert hex to decimal
            int decimal = Integer.parseInt(output, 16);
            //convert the decimal to character
            sb.append((char)decimal);

            temp.append(decimal);
        }

        return sb.toString();
    }
}
