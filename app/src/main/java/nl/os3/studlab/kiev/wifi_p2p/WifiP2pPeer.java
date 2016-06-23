package nl.os3.studlab.kiev.wifi_p2p;

import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;

public class WifiP2pPeer {
    private final int BUFFER_SIZE = 65536;
    private ByteBuffer sendBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    private ByteBuffer recvBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    private int seqNumber = 0;
    private int ackNumber = 0;
    private long lastSeen;
    private ArrayDeque<byte[]> packetQueue = new ArrayDeque<>();
    private Collection<WifiP2pServiceInfo> serviceSet = new ArrayList<>();

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

    public void updateSequence(int ackReceived) {
        int bytesAcknowledged = ackReceived - seqNumber;
        sendBuffer.position(bytesAcknowledged);
        sendBuffer.compact();
        seqNumber += bytesAcknowledged;
    }

    public int getSequenceNumber() {
        return seqNumber;
    }

    public void setServiceSet(Collection<WifiP2pServiceInfo> serviceSet) {
        this.serviceSet = serviceSet;
    }

    public Collection<WifiP2pServiceInfo> getServiceSet() {
        return serviceSet;
    }

    public void sendPacket(ByteBuffer data) {
        sendBuffer.putShort((short) data.remaining());
        sendBuffer.put(data);
    }

    public byte[] getPostData(int maxPostData) {
        int count = Math.min(maxPostData, sendBuffer.remaining());
        byte[] postData = new byte[count];

        for (int i = 0; i < count; i++) {
            postData[i] = sendBuffer.get(i);
        }
        return postData;
    }

    public void recv(byte[] data) {
        ackNumber += data.length;
        recvBuffer.put(data);
    }

    public byte[] recvPacket() {
        if (recvBuffer.remaining() > 2) {
            short packetSize = recvBuffer.getShort(0);
            if (recvBuffer.remaining() >= packetSize + 2) {
                byte[] packet = new byte[packetSize];
                recvBuffer.position(2);
                recvBuffer.get(packet);
                recvBuffer.compact();
                return packet;
            }
        }
        return null;
    }
}
