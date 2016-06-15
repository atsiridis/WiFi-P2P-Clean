package nl.os3.studlab.kiev.wifi_p2p;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;

import java.util.Map;

public class WifiP2pPeer {
    private final String remoteAddress;
    private int sendSequence = 0;
    private int recvSequence = 0;
    private String localSID;
    // TODO: Would this be better as a queue?
    private Map<Integer,WifiP2pServiceInfo> services;
    private WifiP2pServiceRequest currentServiceRequest;
    // TODO: Buffer needs to be a queue
    private byte[] recvBuffer;
    private long lastSeen;

    WifiP2pPeer(WifiP2pDevice peer) {
        remoteAddress = peer.deviceAddress;
        // TODO: Check that prefix matches
        localSID = peer.deviceName.substring(7);
        resetLastSeen();
    }

    public void resetLastSeen() {
        lastSeen = System.nanoTime();
    }
    public long getLastSeen(){
        return lastSeen;
    }
    public String getSID() {
        return localSID;
    }

    public int getRecvSequence() {
        return recvSequence;
    }

    public void incrementRecvSequence() {
        recvSequence++;
    }

    public int getSendSequence() {
        return sendSequence;
    }

    public void incrementSendSequence() {
        sendSequence++;
    }
}
