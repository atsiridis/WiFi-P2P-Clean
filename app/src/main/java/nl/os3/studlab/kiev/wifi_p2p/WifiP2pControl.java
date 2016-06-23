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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.ByteBuffer;
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

public class WifiP2pControl {
    private final String TAG = "OS3";
    private WifiP2pManager manager;
    private Channel channel;
    private String localSID;
    private Map<String,WifiP2pPeer> peerMap = new ConcurrentHashMap<>();
    private Timer checkLastSeenPeer;
    private IntentFilter intentFilter = new IntentFilter();
    private Timer serviceDiscoveryTimer = new Timer();

    private final int UNSPECIFIED_ERROR = 500;
    private final int MAX_SERVICE_LENGTH;
    private final int MAX_BINARY_DATA_SIZE;
    private final int MAX_FRAGMENT_LENGTH;
    private final long expiretime = 240000000000L; // 4 min
    private final long checkPeerLostInterval = 10000L; // 10 sec
    // TODO: Find best value for these intervals
    private final int MAX_SERVICE_DISCOVERY_INTERVAL = 15000; // in milliseconds
    private final int MIN_SERVICE_DISCOVERY_INTERVAL = 10000; // in milliseconds
    private final boolean LEGACY_DEVICE;

    public WifiP2pControl() {
        manager = (WifiP2pManager) WiFiApplication.context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(WiFiApplication.context, WiFiApplication.context.getMainLooper(), null);
        localSID = generateRandomHexString(16);
        LEGACY_DEVICE = (Build.VERSION.SDK_INT < 20);
        MAX_SERVICE_LENGTH = (LEGACY_DEVICE) ? 764 : 948;
        MAX_FRAGMENT_LENGTH = (LEGACY_DEVICE) ? 187 : MAX_SERVICE_LENGTH;
        MAX_BINARY_DATA_SIZE = MAX_SERVICE_LENGTH * 6 / 8; //(due to Base64 Encoding)

        stopDeviceDiscovery();
        clearLocalServices();
        clearServiceRequests();

        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        setResponseListener();
        addServiceRequest();
    }

    /* Init */

    private void setResponseListener() {
        UpnpServiceResponseListener upnpServiceResponseListener = new UpnpServiceResponseListener() {
            @Override
            public void onUpnpServiceAvailable(List<String> uniqueServiceNames, WifiP2pDevice srcDevice) {
                if (srcDevice.deviceName.length() != 22) {
                    Log.e(TAG,"ERROR: Unexpected Device Name: " + srcDevice.toString());
                } else {
                    parseResponse(uniqueServiceNames, srcDevice.deviceName.substring(6));
                }
            }
        };

        manager.setUpnpServiceResponseListener(channel,upnpServiceResponseListener);
        Log.d(TAG, "Initialized UPnP Service Listeners");
    }

    /* Receive Data */

    private void parseResponse(List<String> services, String remoteSID) {
        int sequenceNumber = -1;
        int newSequenceNumber;
        int ackNumber=0;
        boolean fault = false;
        String base64data = "";
        String serviceType;
        Collections.sort(services);
        WifiP2pPeer peer = peerMap.get(remoteSID);
        boolean updatePost = false;

        resetServiceDiscoveryTimer();
        // TODO: Check for valid packet structure
        // TODO: Check for changes to sequence or ack between fragments
        for (String service : services) {
            serviceType = service.substring(43,44);

            if (serviceType.equals("X")) {
                //Log.d(TAG,"Data Received: " + remoteSID + "::" + service);
                newSequenceNumber = Integer.valueOf(service.substring(19, 23), 16);
                ackNumber = Integer.valueOf(service.substring(9, 13), 16);
                if (sequenceNumber == -1 || sequenceNumber == newSequenceNumber) {
                    sequenceNumber = newSequenceNumber;
                    base64data += service.substring(44);
                } else {
                    Log.e(TAG, "Discarding Malformed Data");
                    fault = true;
                }
            }
        }

        if (!fault) {
            Log.d(TAG,"Data Received from: " + remoteSID
                    + ", Ack: " + ackNumber
                    + ", Seq: " + sequenceNumber
                    + ", Data: " + base64data);
            if (base64data.length() + sequenceNumber > peer.getAckNumber()) {
                Log.d(TAG,"New Sequence Received from " + remoteSID);
                byte[] bytes  = Base64.decode(base64data, Base64.DEFAULT);
                peer.recv(bytes);
                updatePost = true;
                byte[] packet = peer.recvPacket();
                try {
                    receivedPacket(hexStringToBytes(remoteSID), packet);
                    peerMap.get(remoteSID).incrementAckNumber();
                    updatePost = true;
                } catch (IOException e) {
                    Log.e(TAG, e.getMessage(), e);
                }
            }

            if (ackNumber == peer.getCurrentSequenceNumber() + 1) {
                Log.d(TAG, "New Ack Received (" + ackNumber + ") from " + remoteSID);
                peer.removePacket();
                updatePost = true;
            }
        if (ackNumber > peer.getSequenceNumber()) {
            Log.d(TAG,"New Ack Received (" + ackNumber + ") from " + remoteSID);
            peer.updateSequence(ackNumber);
            updatePost = true;
        }

            if (updatePost) {
                updatePost(remoteSID);
            }
        }
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
        Random randomGenerator = new Random();
        serviceDiscoveryTimer = new Timer();
        long interval = MIN_SERVICE_DISCOVERY_INTERVAL + randomGenerator.nextInt(MAX_SERVICE_DISCOVERY_INTERVAL-MIN_SERVICE_DISCOVERY_INTERVAL);
        serviceDiscoveryTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                startServiceDiscovery();
                resetServiceDiscoveryTimer();
            }
        }, interval);
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

    private void addServiceRequest() {
        String localID  = localSID.substring(0, 4) + "-" + localSID.substring(4);
        String query = String.format(Locale.ENGLISH, "-%s::X",  localID);
        WifiP2pUpnpServiceRequest serviceRequest = WifiP2pUpnpServiceRequest.newInstance(query);
        Log.d(TAG,"Adding Service Request: " + query);
        addServiceRequest(serviceRequest);
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

    private void queuePacket(String remoteSID, ByteBuffer bytes) {
        WifiP2pPeer peer = peerMap.get(remoteSID);
        peer.sendPacket(bytes);
        updatePost(remoteSID);
    }

    private void updatePost(String remoteSID) {
        WifiP2pPeer peer = peerMap.get(remoteSID);
        byte[] postData = peer.getPostData(MAX_BINARY_DATA_SIZE);
        String base64Data = Base64.encodeToString(postData, Base64.NO_WRAP | Base64.NO_PADDING);
        String uuid;
        String uuidPrefix = String.format(Locale.ENGLISH, "%08x", peer.getAckNumber());
        String uuidSuffix = remoteSID.substring(0,4) + "-" + remoteSID.substring(4);
        int sequenceNumber = peer.getSequenceNumber();
        String device = "";
        String service;
        int fragmentNumber = 0;
        WifiP2pUpnpServiceInfo serviceInfo;
        ArrayList<String> services;
        ArrayList<WifiP2pServiceInfo> serviceInfos = new ArrayList<>();
        int stringLength = base64Data.length();
        int start = 0;
        int end = MAX_FRAGMENT_LENGTH;
        boolean lastFragment = false;

        removeServiceSet(peer.getServiceSet());
        while (!lastFragment) {
            if (end >= stringLength) {
                end = stringLength;
                lastFragment = true;
            }
            uuid = String.format(Locale.ENGLISH, "%s-%04d-%04x-%s", uuidPrefix, fragmentNumber, sequenceNumber, uuidSuffix);
            service = base64Data.substring(start, end);
            services = new ArrayList<>();
            services.add("X" + service);

            serviceInfo = WifiP2pUpnpServiceInfo.newInstance(uuid, device, services);
            addLocalService(serviceInfo);
            Log.d(TAG, "Adding Service Info: " + uuid + "::X" + service);
            serviceInfos.add(serviceInfo);

            start += MAX_FRAGMENT_LENGTH;
            end += MAX_FRAGMENT_LENGTH;
        }
        peer.setServiceSet(serviceInfos);
    }

    private void sendBroadcast(ByteBuffer bytes) {
        for (String key : peerMap.keySet()) {
            queuePacket(key, bytes);
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

    private void removeServiceSet (Collection<WifiP2pServiceInfo> services){
        for (WifiP2pServiceInfo serviceInfo : services){
            removeLocalService(serviceInfo);
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

    // Method Will be provided by AbstractExternalInterface
    private void receivedPacket(byte[] remoteAddress, byte[] data) throws IOException{};

    public void up() {
        setDeviceName("SERVAL" + localSID);
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

    public void sendPacket(byte[] remoteAddress, ByteBuffer buffer) {
        if (remoteAddress == null || remoteAddress.length == 0) {
            Log.d(TAG,"Wifi-P2P: Sending Broadcast Packet");
            sendBroadcast(buffer);
        } else {
            String hexRemoteAddress = bytesToHexString(remoteAddress);
            if (peerMap.containsKey(hexRemoteAddress)) {
                Log.d(TAG,"Wifi-P2P: Sending Packet to " + hexRemoteAddress);
                queueData(hexRemoteAddress, buffer);
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
                    peerMap.put(remoteSID,new WifiP2pPeer());
                    Log.d(TAG,"New Peer Found: " + remoteSID +" (" + peer.deviceAddress + ")");
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
                        //This can stay as it is it will not introduce errors but we have to decide
                        removeServiceSet(peerMap.get(kp).getServiceSet());
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
