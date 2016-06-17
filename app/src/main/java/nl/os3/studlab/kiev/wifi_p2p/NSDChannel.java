package nl.os3.studlab.kiev.wifi_p2p;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.*;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;
import android.net.wifi.p2p.nsd.WifiP2pUpnpServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pUpnpServiceRequest;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

public class NSDChannel {
    private final String TAG = "OS3";
    private WifiP2pManager manager;
    private Channel channel;
    private String localSID;
    private Map<String,WifiP2pPeer> peerMap = new ConcurrentHashMap<>();
    private Timer checkLastSeenPeer;
    private ArrayDeque<WifiP2pServiceRequest> legacyRequestQueue = new ArrayDeque<>();
    private WifiP2pServiceRequest legacyCurrentServiceRequest;
    private IntentFilter intentFilter = new IntentFilter();
    private Timer serviceDiscoveryTimer = new Timer();
    private Timer LegacyRotateTimer = new Timer();

    private final int UNSPECIFIED_ERROR = 500;
    private final int MAX_SERVICE_LENGTH = 948;
    private final int MAX_BINARY_DATA_SIZE = MAX_SERVICE_LENGTH * 6 / 8; //(due to Base64 Encoding)
    // Legacy devices (Pre API 21) have different limits.
    private final int LEGACY_MAX_SERVICE_LENGTH = 764;
    private final int LEGACY_MAX_BINARY_DATA_SIZE = LEGACY_MAX_SERVICE_LENGTH * 6 / 8; // (due to Base64 Encoding)
    private final int LEGACY_MAX_FRAGMENT_LENGTH = 187;
    private final long expiretime = 240000000000L; // 4 min
    private final long checkPeerLostInterval = 10000L; // 10 sec
    // TODO: Find best value for thise intervals
    private final long SERVICE_DISCOVERY_INTERVAL = 15000; // in milliseconds
    private final long ROTATE_LEGACY_INTERVAL = 15000; // in milliseconds
    private final int ackThresh = 50; //packet base
    private final boolean legacy;

    public NSDChannel() {
        manager = (WifiP2pManager) WiFiApplication.context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(WiFiApplication.context, WiFiApplication.context.getMainLooper(), null);
        localSID = generateRandomHexString(16);
        stopDeviceDiscovery();
        clearLocalServices();
        clearServiceRequests();
        legacy = (Build.VERSION.SDK_INT < 20);

        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        setResponseListener();
        Log.e(TAG,"Version: " + Build.VERSION.SDK_INT);
        if (legacy){
            setLegacyRotateTimer();
        }

    }

    /* Init */

    private void setResponseListener() {
        UpnpServiceResponseListener upnpServiceResponseListener = new UpnpServiceResponseListener() {
            @Override
            public void onUpnpServiceAvailable(List<String> uniqueServiceNames, WifiP2pDevice srcDevice) {
                if (srcDevice.deviceName.length() != 22) {
                    Log.e(TAG,"ERROR: Unexpected Device Name: " + srcDevice.toString());
                } else {
                    receiveData(uniqueServiceNames, srcDevice.deviceName.substring(6));
                }
            }
        };

        manager.setUpnpServiceResponseListener(channel,upnpServiceResponseListener);
        Log.d(TAG, "Initialized UPnP Service Listeners");
    }

    /* Receive Data */

    private void receiveData(List<String> services, String remoteSID) {
        int sequenceNumber = -1;
        int newSequenceNumber;
        int ackNumber=0;
        String base64data = "";
        String serviceType = "";
        Collections.sort(services);

        resetServiceDiscoveryTimer();
        for (String service : services) {
            serviceType = service.substring(43,44);
            if (serviceType.equals("X")) {
                Log.d(TAG,"Data Received: " + remoteSID + "::" + service);
                newSequenceNumber = Integer.valueOf(service.substring(19, 23), 16);
                ackNumber = Integer.valueOf(service.substring(9, 13), 16);
                if (sequenceNumber == -1 || sequenceNumber == newSequenceNumber) {
                    sequenceNumber = newSequenceNumber;
                    base64data += service.substring(44);
                } else {
                    Log.e(TAG, "Unexpected Sequence Number Change: " + services.toString());
                    System.exit(UNSPECIFIED_ERROR);
                }
            } else if (serviceType.equals("S")) {
                Log.d(TAG,"Keep Alive Received From: " + remoteSID);
            } else {
                Log.d(TAG,"Ignoring Unknown Service Type: " + serviceType);
            }
        }

        if (sequenceNumber == peerMap.get(remoteSID).getRecvSequence()) {
            if (!base64data.equals("")){
                byte[] bytes  = Base64.decode(base64data, Base64.DEFAULT);
                receivedPacket(hexStringToBytes(remoteSID), bytes);
            }
            peerMap.get(remoteSID).incrementRecvSequence();
            removeCollectionLocalServices(peerMap.get(remoteSID).removeServicesBefore(ackNumber));
            if (peerMap.get(remoteSID).getAckThreshold() > ackThresh){
                //TODO: Send Ack packet
                postStringData("",remoteSID);
            }

            removeServiceRequest(remoteSID);
            addServiceRequest(remoteSID);
            if (legacy){
                resetLegacyRotateTimer();
                rotateServiceRequestQueue();
            }
        } else if (sequenceNumber != -1 ){
            Log.e(TAG,"Unexpected Sequence Number: " + sequenceNumber);
        }
    }

    private void receivedPacket(byte[] remoteAddress, byte[] data) {
        // Method Will be provided by AbstractExternalInterface
    }

    /* Control */

    private void startDeviceDiscovery() {
        manager.discoverPeers(channel, new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG,"Starting Device Discovery");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG,"Starting Device Discovery Failed (" + reason + ")!");
                startDeviceDiscovery();
            }
        });
    }

    private void stopDeviceDiscovery() {
        manager.stopPeerDiscovery(channel, new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG,"Stopping Device Discovery");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG,"Stopping Device Discovery Failed (" + reason + ")!");
            }
        });
    }

    private void startServiceDiscovery() {
        manager.discoverServices(channel, new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG,"Starting Service Discovery");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG,"Starting Service Discovery Failed (" + reason + ")!");
            }
        });
    }

    private void setServiceDiscoveryTimer() {
        serviceDiscoveryTimer = new Timer();
        serviceDiscoveryTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                startServiceDiscovery();
            }
        }, SERVICE_DISCOVERY_INTERVAL, SERVICE_DISCOVERY_INTERVAL);
    }

    private void resetServiceDiscoveryTimer() {
        serviceDiscoveryTimer.cancel();
        setServiceDiscoveryTimer();
    }

    /* Service Requests */

    private void addServiceRequest(WifiP2pServiceRequest serviceRequest) {
        manager.addServiceRequest(channel, serviceRequest, new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Service Request Added");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "Failed to Add Service Request");
            }
        });
    }

    private void addServiceRequest(String remoteSID) {
        int sequenceNumber = peerMap.get(remoteSID).getRecvSequence();
        String pairID = String.format("%016x", new BigInteger(localSID,16).xor(new BigInteger(remoteSID,16)));
        pairID = pairID.substring(0, 4) + "-" + pairID.substring(4);
        String query = String.format(Locale.ENGLISH, "-%04d-%s::X", sequenceNumber, pairID);
        WifiP2pUpnpServiceRequest serviceRequest = WifiP2pUpnpServiceRequest.newInstance(query);
        Log.d(TAG,"Adding Service Request: " + query);
        peerMap.get(remoteSID).setCurrentServiceRequest(serviceRequest);
        if (legacy) {
            legacyRequestQueue.add(serviceRequest);
        } else {
            addServiceRequest(serviceRequest);
        }
    }

    private void rotateServiceRequestQueue() {
        if (legacyCurrentServiceRequest != null) {
            removeServiceRequest(legacyCurrentServiceRequest);
        }
        if (!legacyRequestQueue.isEmpty()) {
            legacyCurrentServiceRequest = legacyRequestQueue.remove();
            addServiceRequest(legacyCurrentServiceRequest);
            legacyRequestQueue.add(legacyCurrentServiceRequest);
        }
    }

    private void setLegacyRotateTimer() {
        LegacyRotateTimer = new Timer();
        LegacyRotateTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                rotateServiceRequestQueue();
            }
        }, ROTATE_LEGACY_INTERVAL-2, ROTATE_LEGACY_INTERVAL);
    }

    private void resetLegacyRotateTimer() {
        Log.d(TAG,"Reseting Legacy Rotate Timer");
        LegacyRotateTimer.cancel();
        setLegacyRotateTimer();
    }

    private void removeServiceRequest(String remoteSID){
        WifiP2pServiceRequest request = peerMap.get(remoteSID).getCurrentServiceRequest();
        if(legacy){
            legacyRequestQueue.remove(request);

        }else{
            removeServiceRequest(request);
        }
    }

    private void removeServiceRequest(WifiP2pServiceRequest serviceRequest) {
        manager.removeServiceRequest(channel, serviceRequest, new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Service Request Removed");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "Failed to Remove Service Request");
            }
        });
    }

    private void clearServiceRequests() {
        manager.clearServiceRequests(channel, new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Service Requests Cleared");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "Failed to Clear Service Requests");
            }
        });
    }

    /* Local Services */

    private void addLocalService(WifiP2pServiceInfo serviceInfo) {
        manager.addLocalService(channel, serviceInfo, new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG,"Local Service Added");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG,"Failed to Add Local Service!");
            }
        });
    }

    private void postBinaryData(byte[] bytes, String remoteSID) {
        int maxDataSize = (Build.VERSION.SDK_INT > 20) ? MAX_BINARY_DATA_SIZE : LEGACY_MAX_BINARY_DATA_SIZE;

        int totalBytes = bytes.length;
        boolean lastChunk = false;
        String sData;
        int start = 0;
        int end = maxDataSize;

        while (!lastChunk) {
            if (end >= totalBytes) {
                end = totalBytes;
                lastChunk = true;
            }
            sData = Base64.encodeToString(bytes, start, end - start, Base64.NO_WRAP | Base64.NO_PADDING);

            postStringData(sData, remoteSID);

            start += maxDataSize;
            end += maxDataSize;
        }
    }

    private void postStringData(String sData, String remoteSID) {
        int maxServiceLength = (Build.VERSION.SDK_INT > 20) ? MAX_SERVICE_LENGTH : LEGACY_MAX_SERVICE_LENGTH;
        int fragmentSize = (Build.VERSION.SDK_INT > 20) ? MAX_SERVICE_LENGTH : LEGACY_MAX_FRAGMENT_LENGTH;
        if (sData.length() > maxServiceLength) {
            Log.e(TAG,"More String Data Then Can be handled in single sequence");
            System.exit(UNSPECIFIED_ERROR);
        }
        String uuid;
        String ackNum = String.format(Locale.ENGLISH, "%04x", peerMap.get(remoteSID).getRecvSequence());
        String uuidPrefix = "0000" + ackNum;
        int sequenceNumber = peerMap.get(remoteSID).getNextSendSequence();
        String device = "";
        String pairID = String.format("%016x", new BigInteger(localSID,16).xor(new BigInteger(remoteSID,16)));
        pairID = pairID.substring(0,4) + "-" + pairID.substring(4);
        String service;
        int fragmentNumber = 0;
        WifiP2pUpnpServiceInfo serviceInfo;
        ArrayList<String> services;
        ArrayList<WifiP2pServiceInfo> serviceInfos = new ArrayList<>();
        int stringLength = sData.length();
        int start = 0;
        int end = fragmentSize;
        boolean lastFragment = false;

        while (!lastFragment) {
            if (end >= stringLength) { end = stringLength; lastFragment = true; }
            uuid = String.format(Locale.ENGLISH, "%s-%04d-%04x-%s", uuidPrefix, fragmentNumber, sequenceNumber, pairID);
            service = sData.substring(start,end);
            services = new ArrayList<>();
            services.add("X" + service);

            serviceInfo = WifiP2pUpnpServiceInfo.newInstance(uuid, device, services);
            addLocalService(serviceInfo);
            Log.d(TAG,"Adding Service Info: " + uuid + "::X" + service);
            serviceInfos.add(serviceInfo);

            start += fragmentSize;
            end += fragmentSize;
        }

        peerMap.get(remoteSID).addService(serviceInfos);
    }

    private void sendBroadcast(byte[] bytes) {
        for (String key : peerMap.keySet()) {
            postBinaryData(bytes, key);
        }
    }

    private void clearLocalServices() {
        manager.clearLocalServices(channel, new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG,"Local Services Cleared");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG,"Failed to Clear Local Services!");
            }
        });
    }

    private void removeCollectionLocalServices (Collection<WifiP2pServiceInfo> services){
        for (WifiP2pServiceInfo serviceinfo :  services){
            removeLocalService(serviceinfo);
        }
    }

    private void removeLocalService(WifiP2pServiceInfo serviceinfo) {
        manager.removeLocalService(channel, serviceinfo ,new ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG,"Local Service removed");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG,"Failed to remove Local Service!");
            }
        });
    }

    /* Interface Implementation */

    public void up() {
        setDeviceName("SERVAL" + localSID);
        createDefaultServices();
        startDeviceDiscovery();
        checkLostPeers();
        WiFiApplication.context.registerReceiver(receiver,intentFilter);
        setServiceDiscoveryTimer();
        WiFiApplication.context.setSID(localSID); // For Debugging
    }

    public void down() {
        serviceDiscoveryTimer.cancel();
        clearLocalServices();
        clearServiceRequests();
        peerMap.clear();
        WiFiApplication.context.unregisterReceiver(receiver);
        stopDeviceDiscovery();
        checkLastSeenPeer.cancel();
        setDeviceName(Build.MODEL);
    }

    public void send(byte[] remoteAddress, byte[] buffer) {
        if (remoteAddress == null || remoteAddress.length == 0) {
            sendBroadcast(buffer);
        } else {
            String hexRemoteAddress = bytesToHexString(remoteAddress);
            if (peerMap.containsKey(hexRemoteAddress)) {
                postBinaryData(buffer, hexRemoteAddress);
            } else {
                Log.w(TAG,"Discarding Data To Unknown Address: " + hexRemoteAddress);
            }
        }
    }

    /* Util */

    private String bytesToHexString(byte[] bytes) {
        return String.format("%016x", new BigInteger(1,bytes));
    }

    private byte[] hexStringToBytes(String hexString) {
        // NOTE: Returned byte array length is not fixed!
        return new BigInteger(hexString,16).toByteArray();
    }

    private String generateRandomHexString(int length) {
        Random randomGenerator = new Random();
        String hexString = "";
        for (int i = 0; i < length; i++) {
            hexString += Integer.toHexString(randomGenerator.nextInt(16));
        }
        return hexString;
    }

    private void createDefaultServices() {
        String uuid = "00000000-0000-0000-0000-000000000000";
        String device = "";
        String service = "S";
        ArrayList<String> services = new ArrayList<>();
        services.add(service);
        String query = uuid + "::" + service;
        WifiP2pUpnpServiceInfo serviceInfo = WifiP2pUpnpServiceInfo.newInstance(uuid, device, services);
        WifiP2pUpnpServiceRequest serviceRequest = WifiP2pUpnpServiceRequest.newInstance(query);
        addLocalService(serviceInfo);
        addServiceRequest(serviceRequest);
    }

    /* Reflection */

    private void setDeviceName(String devName) {
        try {
            Class[] paramTypes = new Class[3];
            paramTypes[0] = Channel.class;
            paramTypes[1] = String.class;
            paramTypes[2] = ActionListener.class;
            Method setDeviceName = manager.getClass().getMethod(
                    "setDeviceName", paramTypes);
            setDeviceName.setAccessible(true);

            Object arglist[] = new Object[3];
            arglist[0] = channel;
            arglist[1] = devName;
            arglist[2] = new ActionListener() {

                @Override
                public void onSuccess() {
                    //Log.d(TAG,"setDeviceName succeeded");
                }

                @Override
                public void onFailure(int reason) {
                    Log.d(TAG,"setDeviceName failed");
                }
            };

            setDeviceName.invoke(manager, arglist);

        } catch (NoSuchMethodException | InvocationTargetException | IllegalArgumentException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    /* Intent Handling */

    private void updatePeerList(WifiP2pDeviceList devices) {
        Collection<WifiP2pDevice> peers = devices.getDeviceList();
        String remoteSID;

        for (WifiP2pDevice peer : peers) {
            if (peer.deviceName.matches("SERVAL[[0-9][a-f]]{16}")) {
                remoteSID = peer.deviceName.substring(6);
                if (!peerMap.containsKey(remoteSID)) {
                    peerMap.put(remoteSID,new WifiP2pPeer(peer));
                    Log.d(TAG,"New Peer Found: " + remoteSID);
                    addServiceRequest(remoteSID);
                } else {
                    peerMap.get(remoteSID).resetLastSeen();
                }
            }
        }
        WiFiApplication.context.setPeers(peerMap.keySet()); // For Debugging
    }

    private void checkLostPeers() {
        //Log.d(TAG,"Checking for lost peers");
        checkLastSeenPeer = new Timer();
        checkLastSeenPeer.schedule(new TimerTask() {
            @Override
            public void run() {
                //Log.d(TAG,"Checking for lost peers");
                for(String kp : peerMap.keySet()){
                    //Log.d(TAG,"Current time" + System.nanoTime());
                    //Log.d(TAG,"Peer time" + peerMap.get(kp).getLastSeen());
                    if ((System.nanoTime() - peerMap.get(kp).getLastSeen()) >= expiretime){
                        Log.d(TAG,"Deleting peer :" + kp);
                        removeCollectionLocalServices(peerMap.get(kp).getAllServices());
                        if (legacy){
                            legacyRequestQueue.remove(peerMap.get(kp).getCurrentServiceRequest());
                            if (legacyCurrentServiceRequest == peerMap.get(kp).getCurrentServiceRequest()) {
                                rotateServiceRequestQueue();
                            }
                        }else{
                            removeServiceRequest(peerMap.get(kp).getCurrentServiceRequest());
                        }
                        peerMap.remove(kp);
                    }
                }
            }
        },checkPeerLostInterval,checkPeerLostInterval);
    }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive (Context context, Intent intent){
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "INTENT:WIFI_P2P_STATE_CHANGED_ACTION");
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    Log.d(TAG, "WiFi P2P Enabled");
                } else if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                    Log.d(TAG, "WiFi P2P Disabled");
                }
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "INTENT:WIFI_P2P_PEERS_CHANGED_ACTION");
                manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                    @Override
                    public void onPeersAvailable(WifiP2pDeviceList peers) {
                        updatePeerList(peers);
                    }
                });
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "INTENT:WIFI_P2P_THIS_DEVICE_CHANGED_ACTION");
                WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                Log.d(TAG, "Local Device: " + device.deviceName + "(" + device.deviceAddress + ")");
            } else if (WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "INTENT:WIFI_P2P_DISCOVERY_CHANGED_ACTION");
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED) {
                    Log.d(TAG, "Device Discovery Has Started");
                } else if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
                    Log.d(TAG, "Device Discovery Has Stopped");
                    startDeviceDiscovery();
                }
            }
        }
    };
}
