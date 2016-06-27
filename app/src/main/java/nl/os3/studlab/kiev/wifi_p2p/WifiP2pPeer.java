package nl.os3.studlab.kiev.wifi_p2p;

import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;

public class WifiP2pPeer {
    private final String TAG = "OS3";
    private final int BUFFER_SIZE = 65536;
    private ByteBuffer sendBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    private ByteBuffer recvBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    private int seqNumber = 0;
    private int ackNumber = 0;
    private long lastSeen;
    private long firstSeen; // For Throughput Measurement
    private Collection<WifiP2pServiceInfo> serviceSet = new ArrayList<>();

    WifiP2pPeer() {
        resetLastSeen();
        firstSeen = System.nanoTime(); // For Throughput Measurement
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
        sendBuffer.flip();
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

    public void queuePacket(ByteBuffer packet) {
        sendBuffer.putShort((short) packet.remaining());
        sendBuffer.put(packet);
    }

    public byte[] getPostData(int maxPostData) {
        int count = Math.min(maxPostData, sendBuffer.position());
        byte[] postData = new byte[count];

        for (int i = 0; i < count; i++) {
            postData[i] = sendBuffer.get(i);
        }
        return postData;
    }

    public void recvData(int seqNumber, byte[] data) {
        int offset = ackNumber - seqNumber;
        int count = data.length - offset;
        recvBuffer.put(data, offset, count);
        ackNumber += count;
        float tp = ackNumber / ((System.nanoTime() - firstSeen) / 1_000_000_000F);
        Log.d(TAG, String.format("Throughput: %.3f   Acknumber: %d    Time: %.3f", tp , ackNumber, (System.nanoTime() - firstSeen) / 1_000_000_000F));
    }

    public byte[] getPacket() {
        if (recvBuffer.remaining() > 2) {
            short packetSize = recvBuffer.getShort(0);
            if (recvBuffer.position() >= packetSize + 2) {
                byte[] packet = new byte[packetSize];
                recvBuffer.flip();
                recvBuffer.position(2);
                recvBuffer.get(packet);
                recvBuffer.compact();
                return packet;
            }
        }
        return null;
    }
}
