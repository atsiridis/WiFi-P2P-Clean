package nl.os3.studlab.kiev.wifi_p2p;

import android.app.Application;
import android.util.Log;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class WiFiApplication extends Application {
    private final String TAG = "OS3";
    private Random randomGenerator = new Random();
    private final int ERROR_UNSPECIFIED = 500;
    public NSDChannel nsd;
    public static WiFiApplication context;
    private WiFiActivity activity;
    private int numBroadCasts = 0;
    private Timer broadcastTimer;

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;

        Log.d(TAG,"################ Initializing ################");

        nsd = new NSDChannel();
        nsd.up();

        //setTimer();

    }

    private void setTimer() {
        // TODO: Change Run interval to a varable
        broadcastTimer = new Timer();
        broadcastTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (numBroadCasts < 100) {
                    sendBroadcast();
                    numBroadCasts++;
                }
            }
        },60000,5000);
    }

    public void sendTest(String remoteSID) {
        byte[] bytes = generateRandomBytes(64);
        Log.d(TAG,"Sending Data to " + remoteSID + ": md5sum[" + md5sum(bytes) + "]");
        nsd.send(new BigInteger(remoteSID,16).toByteArray(), bytes);
    }

    public void sendBroadcast() {
        byte[] bytes = generateRandomBytes(64);
        Log.d(TAG,"Broadcasting: md5sum[" + md5sum(bytes) + "]");
        nsd.send(null, bytes);
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

    private String bytesToHexString(byte[] bytes) {
        String hexString = "";
        for (Byte b: bytes) {
            hexString += String.format("%02x",b.intValue() & 0xFF);
        }
        return hexString;
    }

    private String md5sum(byte[] bytes) {
        try {
            MessageDigest digester = MessageDigest.getInstance("MD5");
            return bytesToHexString(digester.digest(bytes));
        } catch (Exception e) {
            Log.wtf(TAG,"Exception: " + e);
            exit(ERROR_UNSPECIFIED);
            return "";
        }
    }
}
