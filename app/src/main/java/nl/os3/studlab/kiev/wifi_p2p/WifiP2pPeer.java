package nl.os3.studlab.kiev.wifi_p2p;

import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

public class WifiP2pPeer {
    private final int BUFFER_SIZE = 65536;
    private final int POST_SIZE = 128;
    private int seqNumber = 0;
    private int ackNumber = 0;
    private long lastSeen;
    private ArrayDeque<byte[]> packetQueue = new ArrayDeque<>();
    private Collection<WifiP2pServiceInfo> serviceSet = new ArrayList<>();
    // TODO: Set Current Data Size based on MTU

    private int dataOffset = 0;
    private ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);

    WifiP2pPeer() {
        resetLastSeen();
    }

    public void resetLastSeen() {
        lastSeen = System.nanoTime();
    }

    public long getLastSeen(){
        return lastSeen;
    }

    public int getAckNumber() {
        return ackNumber;
    }

    public void incrementAckNumber() {
        ackNumber++;
    }

    public int getCurrentSequenceNumber() {
        return seqNumber;
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
            seqNumber++;
            return packetQueue.remove();
        }
    }

    public void setServiceSet(Collection<WifiP2pServiceInfo> serviceSet) {
        this.serviceSet = serviceSet;
    }

    public Collection<WifiP2pServiceInfo> getServiceSet() {
        return serviceSet;
    }

    public void write(ByteBuffer src) {
        buffer.put(src);
    }

    public Byte[] getPostData() {
        ArrayList<Byte> postData = new ArrayList<>();
        int count = Math.min(POST_SIZE - dataOffset, buffer.limit());
        for (int i = 0; i < count; i++) {
            postData.add(buffer.get(i));
        }
        buffer.get(dst);
    }
}
