package nl.os3.studlab.kiev.wifi_p2p;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;

public class WifiP2pPeer {
    private int sendSequence = 0;
    private int recvSequence = 0;
    private long lastSeen;
    private ArrayDeque<byte[]> packetQueue = new ArrayDeque<>();
    private Collection<WifiP2pServiceInfo> serviceSet = new ArrayList<>();

    WifiP2pPeer(WifiP2pDevice peer) {
        resetLastSeen();
    }

    public void resetLastSeen() {
        lastSeen = System.nanoTime();
    }

    public long getLastSeen(){
        return lastSeen;
    }

    public int getRecvSequence() {
        return recvSequence;
    }

    public void incrementRecvSequence() {
        recvSequence++;
    }

    public int getCurrentSequenceNumber() {
        return sendSequence;
    }

    public void addPacket(byte[] packet) {
        packetQueue.add(packet);
    }

    public byte[] getPacket() {
        if (packetQueue.isEmpty()) {
            return new byte[0];
        } else {
            return packetQueue.peek();
        }
    }

    public boolean isNextPacket() {
        return !packetQueue.isEmpty();
    }

    public byte[] removePacket() {
        if (packetQueue.isEmpty()) {
            return new byte[0];
        } else {
            sendSequence++;
            return packetQueue.remove();
        }
    }

    public void setServiceSet(Collection serviceSet) {
        this.serviceSet = serviceSet;
    }

    public Collection<WifiP2pServiceInfo> getServiceSet() {
        return serviceSet;
    }

}
