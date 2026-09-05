/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class ServerDiscovery {
    private static final int SERVER_DISCOVERY_TIMEOUT = 1500;
    private static final int HTTP_PROBE_CONNECT_TIMEOUT = 300;
    private static final int HTTP_PROBE_READ_TIMEOUT = 500;

    public static class Server implements Comparable<Server> {
        public static final int DEFAULT_PORT = 9000;
        public String ip = "";
        public String name = "";
        public int port = DEFAULT_PORT;

        private static String getString(JSONObject json, String key) {
            try {
                return json.getString(key);
            } catch (JSONException e) {
                return "";
            }
        }

        private static int getPort(JSONObject json) {
            try {
                return json.getInt("port");
            } catch (JSONException e) {
                return Server.DEFAULT_PORT;
            }
        }

        public Server(String str) {
            Utils.debug("DECODE:"+str);
            if (str != null) {
                try {
                    JSONObject json = new JSONObject(str);
                    ip = getString(json, "ip");
                    name = getString(json, "name");
                    port = getPort(json);
                } catch (JSONException ignored) {
                }
            }
        }

        public Server(String ip, int port, String name) {
            this.ip=ip;
            this.port=port;
            this.name=name;
        }

        public Server(DatagramPacket pkt) {
            ip = pkt.getAddress().getHostAddress();

            // Try to get name of server for packet
            int pktLen = pkt.getLength();
            byte[] bytes = pkt.getData();

            // Look for NAME:<Name> in list of key:value pairs
            for(int i=1; i < pktLen; ) {
                if (i + 5 > pktLen) {
                    break;
                }

                // Extract 4 bytes
                String key = new String(bytes, i, 4);
                i += 4;

                int valueLen = bytes[i++] & 0xFF;
                if (i + valueLen > pktLen) {
                    break;
                }

                if (key.equals("NAME")) {
                    name = new String(bytes, i, valueLen);
                    Utils.debug("Name:"+name);
                } else if (key.equals("JSON")) {
                    try {
                        port = Integer.parseInt(new String(bytes, i, valueLen));
                        Utils.debug("Port:"+port);
                    } catch (NumberFormatException ignored) {
                    }
                }
                i += valueLen;
            }
        }

        public boolean isEmpty() {
            return null==ip || ip.isEmpty();
        }

        @Override
        public int compareTo(@NonNull Server o) {
            return null==ip ? (o.ip==null ? 0 : -1) : ip.compareTo(o.ip);
        }

        public boolean equals(Server o) {
            return Objects.equals(ip, o.ip);
        }

        public String describe() {
            if (null==name || name.isEmpty()) {
                return address();
            }
            return name+" ("+address()+")";
        }

        public String address() {
            return ip + (DEFAULT_PORT==port ? "" : (":"+port));
        }

        public String encode() {
            try {
                JSONObject json = new JSONObject();
                json.put("ip", ip);
                json.put("name", name);
                json.put("port", port);
                return json.toString(0);
            } catch (JSONException e) {
                return ip;
            }
        }
    }

    class DiscoveryRunnable implements Runnable {
        private volatile boolean active = false;
        private final WifiManager wifiManager;
        private final ConnectivityManager connectivityManager;
        private final List<Server> servers = new LinkedList<>();

        DiscoveryRunnable(WifiManager wifiManager, ConnectivityManager connectivityManager) {
            this.wifiManager = wifiManager;
            this.connectivityManager = connectivityManager;
        }

        // Returns true if a server was found and discoverAll is false (caller should stop).
        private boolean discoverOnInterface(Network network, InetAddress localAddr, InetAddress broadcastAddr, byte[] req, String label) {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.setSoTimeout(SERVER_DISCOVERY_TIMEOUT);
                if (null!=network) {
                    network.bindSocket(socket);
                }
                socket.bind(null!=localAddr ? new InetSocketAddress(localAddr, 0) : new InetSocketAddress(0));
                Utils.debug("Discover via " + label + " -> " + broadcastAddr.getHostAddress());
                DatagramPacket reqPkt = new DatagramPacket(req, req.length, broadcastAddr, 3483);
                socket.send(reqPkt);
                byte[] resp = new byte[256];
                DatagramPacket respPkt = new DatagramPacket(resp, resp.length);
                for (;;) {
                    try {
                        socket.receive(respPkt);
                        if (resp[0]==(byte)'E') {
                            Server server = new Server(respPkt);
                            if (!servers.contains(server)) {
                                servers.add(server);
                                if (!discoverAll) {
                                    return true;
                                }
                            }
                        }
                    } catch (IOException e) {
                        break;
                    }
                }
            } catch (Exception e) {
                Utils.debug("Discovery failed via " + label + ": " + e.getMessage());
            } finally {
                if (null!=socket) {
                    socket.close();
                }
            }
            return false;
        }

        private int inet4ToInt(InetAddress address) {
            byte[] bytes = address.getAddress();
            return ((bytes[0] & 0xff) << 24) |
                   ((bytes[1] & 0xff) << 16) |
                   ((bytes[2] & 0xff) << 8) |
                   (bytes[3] & 0xff);
        }

        private InetAddress intToInet4(int value) {
            byte[] bytes = {
                    (byte) ((value >> 24) & 0xff),
                    (byte) ((value >> 16) & 0xff),
                    (byte) ((value >> 8) & 0xff),
                    (byte) (value & 0xff)
            };
            try {
                return InetAddress.getByAddress(bytes);
            } catch (Exception ignored) {
                return null;
            }
        }

        private InetAddress getBroadcastAddress(InetAddress address, int prefixLength) {
            if (!(address instanceof Inet4Address) || prefixLength < 0 || prefixLength > 30) {
                return null;
            }
            int ip = inet4ToInt(address);
            int mask = prefixLength==0 ? 0 : (-1 << (32 - prefixLength));
            return intToInet4(ip | ~mask);
        }

        private boolean isDiscoveryNetwork(NetworkCapabilities capabilities) {
            return null!=capabilities &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        }

        private List<DiscoveryTarget> getConnectivityTargets() {
            List<DiscoveryTarget> targets = new ArrayList<>();
            if (null==connectivityManager) {
                return targets;
            }
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (!isDiscoveryNetwork(capabilities)) {
                    continue;
                }
                LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
                if (null==linkProperties) {
                    continue;
                }
                String ifaceName = linkProperties.getInterfaceName();
                for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                    InetAddress localAddr = linkAddress.getAddress();
                    InetAddress broadcastAddr = getBroadcastAddress(localAddr, linkAddress.getPrefixLength());
                    if (null!=broadcastAddr && !localAddr.isLoopbackAddress()) {
                        String label = (null==ifaceName ? "network" : ifaceName) + "/" + localAddr.getHostAddress();
                        targets.add(new DiscoveryTarget(network, localAddr, broadcastAddr, linkAddress.getPrefixLength(), label));
                        try {
                            targets.add(new DiscoveryTarget(network, localAddr, InetAddress.getByName("255.255.255.255"), linkAddress.getPrefixLength(), label + "/global"));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            return targets;
        }

        private List<ProbeTarget> getProbeTargets(List<DiscoveryTarget> discoveryTargets) {
            List<ProbeTarget> targets = new ArrayList<>();
            Set<String> scannedSubnets = new HashSet<>();
            Set<String> scannedHosts = new HashSet<>();
            for (DiscoveryTarget target : discoveryTargets) {
                if (!(target.localAddr instanceof Inet4Address) || target.prefixLength < 0 || target.prefixLength > 30) {
                    continue;
                }
                int prefixLength = Math.max(target.prefixLength, 24);
                int mask = prefixLength==0 ? 0 : (-1 << (32 - prefixLength));
                int local = inet4ToInt(target.localAddr);
                int network = local & mask;
                int broadcast = network | ~mask;
                String subnetKey = target.network + "/" + network + "/" + prefixLength;
                if (!scannedSubnets.add(subnetKey)) {
                    continue;
                }
                long first = (network & 0xffffffffL) + 1;
                long last = (broadcast & 0xffffffffL) - 1;
                for (long host = first; host <= last; host++) {
                    if ((int) host == local) {
                        continue;
                    }
                    InetAddress hostAddr = intToInet4((int) host);
                    if (null!=hostAddr && scannedHosts.add(target.network + "/" + hostAddr.getHostAddress())) {
                        targets.add(new ProbeTarget(target.network, hostAddr));
                    }
                }
            }
            return targets;
        }

        private String readResponse(InputStream inputStream) throws IOException {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int total = 0;
            int len;
            while ((len = inputStream.read(buffer)) != -1 && total < 65536) {
                outputStream.write(buffer, 0, len);
                total += len;
            }
            return outputStream.toString("UTF-8");
        }

        private Server probeHttpServer(ProbeTarget target) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("http://" + target.address.getHostAddress() + ":" + Server.DEFAULT_PORT + "/jsonrpc.js");
                connection = (HttpURLConnection) (null!=target.network ? target.network.openConnection(url) : url.openConnection());
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(HTTP_PROBE_CONNECT_TIMEOUT);
                connection.setReadTimeout(HTTP_PROBE_READ_TIMEOUT);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] body = "{\"id\":1,\"method\":\"slim.request\",\"params\":[\"\",[\"serverstatus\",\"0\",\"1\"]]}".getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body);
                }
                if (connection.getResponseCode()==HttpURLConnection.HTTP_OK) {
                    JSONObject json = new JSONObject(readResponse(connection.getInputStream()));
                    if (json.has("result")) {
                        JSONObject result = json.optJSONObject("result");
                        String name = null==result ? "" : result.optString("server_name", "");
                        Utils.debug("Discovered LMS HTTP server " + target.address.getHostAddress());
                        return new Server(target.address.getHostAddress(), Server.DEFAULT_PORT, name);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (null!=connection) {
                    connection.disconnect();
                }
            }
            return null;
        }

        private boolean probeSubnets(List<DiscoveryTarget> discoveryTargets) {
            List<ProbeTarget> targets = getProbeTargets(discoveryTargets);
            if (targets.isEmpty()) {
                return false;
            }
            Utils.debug("Probe " + targets.size() + " local addresses for LMS HTTP");
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(24, targets.size()));
            CompletionService<Server> completionService = new ExecutorCompletionService<>(executor);
            try {
                for (ProbeTarget target : targets) {
                    completionService.submit(() -> probeHttpServer(target));
                }
                for (int i = 0; i < targets.size(); i++) {
                    Server server = completionService.take().get();
                    if (null!=server && !servers.contains(server)) {
                        servers.add(server);
                        if (!discoverAll) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                executor.shutdownNow();
            }
            return false;
        }

        private class DiscoveryTarget {
            final Network network;
            final InetAddress localAddr;
            final InetAddress broadcastAddr;
            final int prefixLength;
            final String label;

            DiscoveryTarget(Network network, InetAddress localAddr, InetAddress broadcastAddr, int prefixLength, String label) {
                this.network = network;
                this.localAddr = localAddr;
                this.broadcastAddr = broadcastAddr;
                this.prefixLength = prefixLength;
                this.label = label;
            }
        }

        private class ProbeTarget {
            final Network network;
            final InetAddress address;

            ProbeTarget(Network network, InetAddress address) {
                this.network = network;
                this.address = address;
            }
        }

        @Override
        public void run() {
            Utils.debug("Discover LMS servers");

            active = true;
            WifiManager.WifiLock wifiLock = wifiManager.createWifiLock(Utils.LOG_TAG);
            wifiLock.acquire();

            try {
                byte[] req = { 'e', 'I', 'P', 'A', 'D', 0, 'N', 'A', 'M', 'E', 0, 'J', 'S', 'O', 'N', 0 };

                // Android hotspot is exposed as a non-default local Network while mobile data
                // remains the default Network. Bind the UDP socket to each local WiFi/Ethernet
                // Network so hotspot broadcasts are routed over the tethering interface.
                boolean stopDiscovery = false;
                List<DiscoveryTarget> connectivityTargets = getConnectivityTargets();
                for (DiscoveryTarget target : connectivityTargets) {
                    if (discoverOnInterface(target.network, target.localAddr, target.broadcastAddr, req, target.label)) {
                        stopDiscovery = true;
                        break;
                    }
                }
                if (!stopDiscovery) {
                    stopDiscovery = probeSubnets(connectivityTargets);
                }

                // Fall back to broadcast addresses from all active non-loopback interfaces for
                // devices that do not expose the local link through ConnectivityManager.
                if (!stopDiscovery) {
                    List<InterfaceAddress> candidates = new ArrayList<>();
                    try {
                        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
                        if (null!=ifaces) {
                            while (ifaces.hasMoreElements()) {
                                NetworkInterface iface = ifaces.nextElement();
                                if (!iface.isLoopback() && iface.isUp()) {
                                    for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                                        if (addr.getBroadcast() != null) {
                                            candidates.add(addr);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    if (candidates.isEmpty()) {
                        discoverOnInterface(null, null, InetAddress.getByName("255.255.255.255"), req, "global");
                    } else {
                        for (InterfaceAddress addr : candidates) {
                            if (discoverOnInterface(null, addr.getAddress(), addr.getBroadcast(), req, addr.getAddress().getHostAddress())) {
                                break;
                            }
                        }
                        if (servers.isEmpty() || discoverAll) {
                            discoverOnInterface(null, null, InetAddress.getByName("255.255.255.255"), req, "global");
                        }
                    }
                }

            } catch (Exception ignored) {
            } finally {
                Utils.verbose("Scanning complete, unlocking WiFi");
                wifiLock.release();
            }

            handler.sendMessage(new Message());
            active = false;
        }

        public boolean isActive() {
            return active;
        }
    }

    final Context context;
    private final boolean discoverAll;
    private final Handler handler;
    private DiscoveryRunnable runnable;

    ServerDiscovery(Context context, boolean discoverAll) {
        this.context = context;
        this.discoverAll = discoverAll;
        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(@NonNull Message unused) {
                discoveryFinished(runnable.servers);
            }
        };
    }

    public void discover() {
        if (runnable!=null && runnable.isActive()) {
            return;
        }
        runnable = new DiscoveryRunnable(
                (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE),
                (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE));
        Thread thread = new Thread(runnable);
        thread.start();
    }

    protected abstract void discoveryFinished(List<Server> servers);
}
