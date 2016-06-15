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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

// TODO: Implement message (packet) passing?
// TODO: Add Local Service Cleanup using Acknowledgements
// TODO: Need Class To Track State for Peers
// TODO: Need to implement timeouts using Timers
// TODO: Implement Broadcast Message
// TODO: How will SIDs be used with UUIDs
// TODO: How to keep device discovery working until we quit
// TODO: What is the format of addresses received from application?
// TODO: Determine how often to rotate service requests on legacy devices

public class NSDChannel {
    private final String TAG = "OS3";
    private WifiP2pManager manager;
    private Channel channel;
    private String localAddress;
    private String localSID;
    private Map<String,WifiP2pPeer> peerMap = new HashMap<>();
    private Timer checkLastSeenPeer;
    private ArrayDeque<WifiP2pServiceRequest> legacyRequestQueue = new ArrayDeque<>();
    private WifiP2pServiceRequest legacyCurrentServiceRequest;

    private final int UNSPECIFIED_ERROR = 500;
    private final int MAX_SERVICE_LENGTH = 948;
    private final int MAX_BINARY_DATA_SIZE = MAX_SERVICE_LENGTH * 6 / 8; //(due to Base64 Encoding)
    // Legacy devices (Pre API 21) have different limits.
    private final int LEGACY_MAX_SERVICE_LENGTH = 764;
    private final int LEGACY_MAX_BINARY_DATA_SIZE = LEGACY_MAX_SERVICE_LENGTH * 6 / 8; // (due to Base64 Encoding)
    private final int LEGACY_MAX_FRAGMENT_LENGTH = 187;
    private final long expiretime = 240000000000L; // 4 min
    private final long checkPeerLostInterval = 10000L; // 10 sec

    public NSDChannel() {
        manager = (WifiP2pManager) WiFiApplication.context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(WiFiApplication.context, WiFiApplication.context.getMainLooper(), null);
        localSID = generateRandomHexString(16);
        stopDeviceDiscovery();
        clearLocalServices();
        clearServiceRequests();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        WiFiApplication.context.registerReceiver(receiver,intentFilter);

        startDeviceDiscovery();
        setResponseListener();
        setDeviceName("SERVAL" + localSID);
        checkLostPeers();
    }

    /* Init */

    private void setResponseListener() {
        UpnpServiceResponseListener upnpServiceResponseListener = new UpnpServiceResponseListener() {
            @Override
            public void onUpnpServiceAvailable(List<String> uniqueServiceNames, WifiP2pDevice srcDevice) {
                receiveData(uniqueServiceNames, srcDevice.deviceAddress);
            }
        };

        manager.setUpnpServiceResponseListener(channel,upnpServiceResponseListener);
        Log.d(TAG, "Initialized UPnP Service Listeners");
    }

    public void stop() {
        clearLocalServices();
        clearServiceRequests();
        WiFiApplication.context.unregisterReceiver(receiver);
        stopDeviceDiscovery();
        setDeviceName(Build.MODEL);
    }

    /* Receive Data */

    private void receiveData(List<String> services, String remoteSID) {
        int sequenceNumber = -1;
        int newSequenceNumber;
        String base64data = "";
        Collections.sort(services);
        for (String service : services) {
            newSequenceNumber = Integer.parseInt(service.substring(24, 28));
            if (sequenceNumber == -1 || sequenceNumber == newSequenceNumber) {
                sequenceNumber = newSequenceNumber;
                base64data += service.substring(44);
            } else {
                Log.e(TAG,"Unexpected Sequence Number Change: " + services.toString());
                System.exit(UNSPECIFIED_ERROR);
            }
        }

        if (sequenceNumber == peerMap.get(remoteSID).getRecvSequence()) {
            byte[] bytes  = Base64.decode(base64data, Base64.DEFAULT);
            // TODO: Write to source specific buffer until complete packet or write directly to application
            // TODO: Update service request and peer object
        } else {
            Log.e(TAG,"Unexpected Sequence Number: " + sequenceNumber);
            System.exit(UNSPECIFIED_ERROR);
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

    // TODO: Figure out when to run this!
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
        boolean legacy = (Build.VERSION.SDK_INT < 20);
        int sequenceNumber = peerMap.get(remoteSID).getRecvSequence();
        String pairID = String.format("%016x", Long.parseLong(localSID, 16) ^ Long.parseLong(remoteSID, 16));
        pairID = pairID.substring(0, 4) + "-" + pairID.substring(4);
        String query = String.format(Locale.ENGLISH, "-%04d-%s::X", sequenceNumber, pairID);
        WifiP2pUpnpServiceRequest serviceRequest = WifiP2pUpnpServiceRequest.newInstance(query);
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

    public void postBinaryData(byte[] bytes, String remoteSID) {
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
        String uuidPrefix = "00000000";
        int sequenceNumber = peerMap.get(remoteSID).getNextSendSequence();
        String device = "";
        String pairID = String.format("%016x",Long.parseLong(localSID,16) ^ Long.parseLong(remoteSID,16));
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
            uuid = String.format(Locale.ENGLISH, "%s-%04d-%04d-%s", uuidPrefix, fragmentNumber, sequenceNumber, pairID);
            service = sData.substring(start,end);
            services = new ArrayList<>();
            services.add("X" + service);

            serviceInfo = WifiP2pUpnpServiceInfo.newInstance(uuid, device, services);
            addLocalService(serviceInfo);
            serviceInfos.add(serviceInfo);

            start += fragmentSize;
            end += fragmentSize;
        }

        peerMap.get(remoteSID).addService(serviceInfos);
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

    /* Util */

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
                    Log.d(TAG,"setDeviceName succeeded");
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
        String sid;

        for (WifiP2pDevice peer : peers) {
            if (peer.deviceName.matches("SERVAL[[0-9][a-f]]{16}")) {
                sid = peer.deviceName.substring(6);
                if (!peerMap.containsKey(sid)) {
                    peerMap.put(sid,new WifiP2pPeer(peer));
                    addServiceRequest(sid);
                } else {
                    peerMap.get(sid).resetLastSeen();
                }
            }
        }
    }
    private void checkLostPeers() {
        Log.d(TAG,"Checking for lost peers");
        checkLastSeenPeer = new Timer();
        checkLastSeenPeer.schedule(new TimerTask() {
            @Override
            public void run() {
                Log.d(TAG,"Checking for lost peers");
                for(String kp : peerMap.keySet()){
                    Log.d(TAG,"Current time" + System.nanoTime());
                    Log.d(TAG,"Peer time" + peerMap.get(kp).getLastSeen());
                    if ((System.nanoTime() - peerMap.get(kp).getLastSeen()) >= expiretime){
                        Log.d(TAG,"Deliting peer :" + kp);
                        for (WifiP2pServiceInfo serviceinfo :  peerMap.get(kp).getAllServices()){
                            removeLocalService(serviceinfo);
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
            /*
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "INTENT:WIFI_P2P_CONNECTION_CHANGED_ACTION");
                WifiP2pInfo conState = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                NetworkInfo netState = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                Log.d(TAG, "EXTRA_WIFI_P2P_INFO:" + conState.toString());
                Log.d(TAG, "EXTRA_NETWORK_INFO:" + netState.toString());
            */
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
