package com.metashare.client;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Bounded, cancellable discovery of untrusted hints. It never opens a stream. */
final class DiscoveryClient {
    interface Listener { void found(HostEndpoint endpoint, int monitors, String name); void complete(String status); }
    private static final byte[] MAGIC = "METUXAD1".getBytes(StandardCharsets.US_ASCII);
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile DatagramSocket socket;
    private volatile boolean cancelled;
    DiscoveryClient(Context context) { this.context = context.getApplicationContext(); }
    void start(Listener listener) { new Thread(() -> scan(listener), "metuxa-discovery").start(); }
    void cancel() { cancelled = true; DatagramSocket s = socket; if (s != null) s.close(); }
    private void scan(Listener listener) {
        WifiManager.MulticastLock lock = null;
        int found = 0;
        String result;
        try (DatagramSocket s = new DatagramSocket()) {
            socket = s;
            if (cancelled) return;
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) { lock = wifi.createMulticastLock("metuxa-discovery"); lock.setReferenceCounted(false); lock.acquire(); }
            s.setBroadcast(true); s.setSoTimeout(350);
            byte[] probe = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(MAGIC).putInt(5).putInt(0).array();
            Set<String> targets = new HashSet<>(); targets.add("255.255.255.255");
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress a : n.getInterfaceAddresses()) if (a.getBroadcast() instanceof Inet4Address) targets.add(a.getBroadcast().getHostAddress());
            }
            DiscoveryBudget budget = new DiscoveryBudget();
            long end = android.os.SystemClock.elapsedRealtime() + 4000, nextProbe = 0;
            while (!cancelled && budget.hasCapacity() && android.os.SystemClock.elapsedRealtime() < end) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now >= nextProbe) {
                    for (String target : targets) try { s.send(new DatagramPacket(probe, probe.length, InetAddress.getByName(target), 7877)); } catch (Exception ignored) { }
                    nextProbe = now + 1200;
                }
                byte[] data = new byte[256]; DatagramPacket packet = new DatagramPacket(data, data.length);
                try { s.receive(packet); } catch (SocketTimeoutException e) { continue; }
                if (!budget.consumeResponse()) break;
                if (packet.getLength() != 80) continue;
                boolean magic = true; for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) magic = false;
                if (!magic) continue;
                ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); b.position(8);
                if (b.getInt() != 5) continue;
                int port = Short.toUnsignedInt(b.getShort()), count = Short.toUnsignedInt(b.getShort());
                if (port < 1 || port > 65533 || count < 1 || count > 3) continue;
                HostEndpoint endpoint = new HostEndpoint(packet.getAddress().getHostAddress(), port);
                if (!budget.addHost(endpoint.key())) continue;
                int length = 0; while (length < 64 && data[16 + length] != 0) length++;
                String label = new String(data, 16, length, StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}\\p{Cf}]", "");
                found++;
                main.post(() -> { if (!cancelled) listener.found(endpoint, count, label); });
            }
            result = found == 0 ? "No PCs found. Start the metuxa server on the same LAN, or enter its address below." : "Scan complete. Verify the fingerprint on your PC before connecting.";
        } catch (Exception e) {
            result = "Discovery unavailable. You can still connect with a manual address.";
        } finally { socket = null; if (lock != null && lock.isHeld()) lock.release(); }
        final String status = result;
        main.post(() -> { if (!cancelled) listener.complete(status); });
    }
}
