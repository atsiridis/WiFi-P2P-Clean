package nl.os3.studlab.kiev.wifi_p2p;

import android.app.Application;
import android.util.Log;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class WiFiApplication extends Application {
    private final String TAG = "OS3";
    private Random randomGenerator = new Random();
    private final int ERROR_UNSPECIFIED = 500;
    private final int MAX_PACKET_SIZE = 1500;
    private final int MIN_PACKET_SIZE = 6;
    public WifiP2pControl wifiP2pControl;
    public static WiFiApplication context;
    private WiFiActivity activity;
    private int numBroadCasts = 0;
    private Timer broadcastTimer;
    private String localSID = "";
    private Collection peers = new ArrayList();

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;

        Log.d(TAG,"################ Initializing ################");

        wifiP2pControl = new WifiP2pControl();
        wifiP2pControl.up();

        //setTimer();
    }

    private void setTimer() {
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
        byte[] bytes = generateRandomBytes(MAX_PACKET_SIZE,MIN_PACKET_SIZE);
        ByteBuffer packet = ByteBuffer.wrap(bytes);
        Log.d(TAG,"Sending Packet To: " + remoteSID
                + ", Len: " + bytes.length
                + ", md5sum[" + md5sum(bytes) + "]");
        wifiP2pControl.sendPacket(new BigInteger(remoteSID,16).toByteArray(), packet);
    }

    public void sendBroadcast() {
        byte[] bytes = generateRandomBytes(MAX_PACKET_SIZE,MIN_PACKET_SIZE);
        ByteBuffer packet = ByteBuffer.wrap(bytes);
        Log.d(TAG,"Broadcasting Packet, Len: " + bytes.length + ", md5sum[" + md5sum(bytes) + "]");
        wifiP2pControl.sendPacket(null, packet);
    }

    public void receivedPacket(byte[] remoteAddress, byte[] packet){
        Log.d(TAG,"Packet Received From: " + bytesToHexString(remoteAddress)
                + ", Len: " + packet.length
                + ", md5sum[" + md5sum(packet) + "]");
    }

    public void setActivity(WiFiActivity activity) {
        this.activity = activity;
        updateActivity();
    }

    public void setPeers(Collection<String> peers) {
        this.peers = peers;
        updateActivity();
    }

    public void setSID(String sid) {
        localSID = sid;
        updateActivity();
    }

    private void updateActivity() {
        if (activity != null) {
            activity.displayPeers(peers);
            activity.displaySID(localSID);
        }
    }

    public void exit(int exitCode) {
        wifiP2pControl.down();
        Log.d(TAG,"Exiting");
        System.exit(exitCode);
    }

    /* Util */

    private byte[] generateRandomBytes(int max, int min) {
        return generateRandomBytes(randomGenerator.nextInt(max-min) + min);
    }

    private byte[] generateRandomBytes(int length) {
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
