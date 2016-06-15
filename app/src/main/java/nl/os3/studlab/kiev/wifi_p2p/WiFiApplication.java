package nl.os3.studlab.kiev.wifi_p2p;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.*;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class WiFiApplication extends Application {
    private final String TAG = "OS3";
    private Random randomGenerator = new Random();
    private final int ERROR_UNSPECIFIED = 500;
    public NSDChannel nsd;
    public static  WiFiApplication context;
    @Override
    public void onCreate() {
        super.onCreate();
        context = this;

        Log.d(TAG,"################ Initializing ################");

        nsd = new NSDChannel();

        Log.d(TAG,"Initialization Complete");
    }

    private byte[] genBytes() {
        byte[] bytes = new byte[258];
        bytes[0] = 0x77;
        bytes[257] = 0x77;
        int byteValue;
        int index = 1;
        for (int i = 0; i < 256; i++) {
            byteValue = 127 - i;
            //if (byteValue == 0x2c) { byteValue = 0xff; }
            bytes[index++] = (byte) byteValue;
        }
        bytes[1] = 0x2c;
        bytes[2] = 0x2c;
        bytes[3] = 0x2c;
        bytes[100] = 0x2c;
        bytes[101] = 0x2c;
        bytes[102] = 0x2c;
        bytes[254] = 0x2c;
        bytes[255] = 0x2c;
        bytes[256] = 0x2c;
        return bytes;
    }

    private String generateRandomHexString(int length) {
        String hexString = "";

        for (int i = 0; i < length; i++) {
            hexString += Integer.toHexString(randomGenerator.nextInt(16));
        }
        return hexString;
    }

    private String bytesToHex(byte[] bytes) {
        String hexString = "";
        for (Byte b: bytes) {
            hexString += String.format("%02x",b.intValue() & 0xFF);
        }
        return hexString;
    }

    private String bytesToString(byte[] bytes) {
        Charset c = Charset.forName("ISO-8859-1");
        return new String(bytes, c);
    }

    private String stringToHex(String byteString) {
        return bytesToHex(stringToBytes(byteString));
    }

    private byte[] stringToBytes(String byteString) {
        Charset c = Charset.forName("ISO-8859-1");
        return byteString.getBytes(c);
    }

    private String md5sum(byte[] bytes) {
        try {
            MessageDigest digester = MessageDigest.getInstance("MD5");
            return bytesToHex(digester.digest(bytes));
        } catch (Exception e) {
            Log.wtf(TAG,"Exception: " + e);
            exit(ERROR_UNSPECIFIED);
            return "";
        }
    }

    public void exit(int exitCode) {
        nsd.down();
        Log.d(TAG,"Exiting");
        System.exit(exitCode);
    }

}
