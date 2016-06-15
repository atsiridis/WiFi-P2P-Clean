package nl.os3.studlab.kiev.wifi_p2p;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class WifiP2pPeer {
    private int sendSequence = 0;
    private int recvSequence = 0;
    private Map<Integer,WifiP2pServiceInfo> serviceMap = new HashMap<>();
    private WifiP2pServiceRequest currentServiceRequest;
    private long lastSeen;

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

    public int getNextSendSequence() {
        return sendSequence;
    }

    public Collection<WifiP2pServiceInfo> getAllServices() {
        Collection<Integer> keys = serviceMap.keySet();
        Collection<WifiP2pServiceInfo> serviceInfos = new ArrayList<>();

        for (int key : keys) {
            serviceInfos.add(serviceMap.get(key));
        }
        return serviceInfos;
    }

    public Collection<WifiP2pServiceInfo> removeServicesBefore(int sequenceNumber) {
        Collection<Integer> keys = serviceMap.keySet();
        Collection<WifiP2pServiceInfo> removedServices = new ArrayList<>();

        for (int key : keys) {
            if (key < sequenceNumber) {
                removedServices.add(serviceMap.get(key));
                serviceMap.remove(key);
            }
        }
        return removedServices;
    }

    public void addService(WifiP2pServiceInfo serviceInfo) {
        serviceMap.put(sendSequence++, serviceInfo);
    }
}
