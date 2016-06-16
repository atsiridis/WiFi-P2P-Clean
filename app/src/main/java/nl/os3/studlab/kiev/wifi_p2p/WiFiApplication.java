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
import java.math.BigInteger;
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
    public static WiFiApplication context;
    private WiFiActivity activity;

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;

        Log.d(TAG,"################ Initializing ################");

        nsd = new NSDChannel();
        nsd.up();

    }

    public void sendTest(String remoteSID) {
        byte[] bytes = generateRandomBytes(64);
        Log.d(TAG,"Sending Data to " + remoteSID + ": md5sum[" + md5sum(bytes) + "]");
        nsd.send(new BigInteger(remoteSID,16).toByteArray(), bytes);
    }

    public void setActivity(WiFiActivity activity) {
        this.activity = activity;
        updatePeerList();
    }

    public void updatePeerList() {
        if (activity != null) {
            activity.display_peers(nsd.getPeers());
        }
    }

    public void exit(int exitCode) {
        nsd.down();
        Log.d(TAG,"Exiting");
        System.exit(exitCode);
    }

    /* Util */

    private byte[] generateRandomBytes(int length) {
        Random randomGenerator = new Random();
        byte[] bytes = new byte[length];
         randomGenerator.nextBytes(bytes);
        return bytes;
    }

    private String bytesToHex(byte[] bytes) {
        String hexString = "";
        for (Byte b: bytes) {
            hexString += String.format("%02x",b.intValue() & 0xFF);
        }
        return hexString;
    }

    private String md5sum(byte[] bytes) {
        try {
            MessageDigest digester = MessageDigest.getInstance("MD5");
            return String.format("%032x", new BigInteger(digester.digest(bytes)));
        } catch (Exception e) {
            Log.wtf(TAG,"Exception: " + e);
            exit(ERROR_UNSPECIFIED);
            return "";
        }
    }
}
