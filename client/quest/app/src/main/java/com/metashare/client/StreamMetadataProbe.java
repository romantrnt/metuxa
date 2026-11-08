package com.metashare.client;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;

/** One pinned AUTH/HELLO exchange. Never sends READY or opens a media receiver. */
final class StreamMetadataProbe {
    interface Listener { void complete(int[] size); }
    interface SocketFactory { SSLSocket open(String pin) throws Exception; }
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), task -> { Thread t = new Thread(task, "MetuxaProbeDns"); t.setDaemon(true); return t; });
    private static final ScheduledThreadPoolExecutor DEADLINE = new ScheduledThreadPoolExecutor(1,
        task -> { Thread t = new Thread(task, "MetuxaProbeDeadline"); t.setDaemon(true); return t; });
    static { DEADLINE.setRemoveOnCancelPolicy(true); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SocketFactory factory;
    private boolean cancelled, started, expired;
    private SocketShutdown closing;
    private Thread worker;
    StreamMetadataProbe() { this(SecureTransport::pinnedSocket); }
    StreamMetadataProbe(SocketFactory factory) { this.factory = factory; }
    synchronized void start(HostEndpoint endpoint, String pin, String token, Listener listener) {
        if (started || cancelled) return;
        started = true;
        worker = new Thread(() -> run(endpoint, pin, token, listener), "MetuxaDisplayProbe"); worker.setDaemon(true); worker.start();
    }
    synchronized void cancel() {
        cancelled = true;
        if (closing != null) closing.request();
        if (worker != null) worker.interrupt();
    }
    private synchronized void expire() {
        expired = true;
        if (closing != null) closing.request();
        if (worker != null) worker.interrupt();
    }
    private synchronized boolean inactive() { return cancelled || expired; }
    private void run(HostEndpoint endpoint, String pin, String token, Listener listener) {
        int[] size = null; boolean ownsMonitor = false;
        Future<?> timeout = DEADLINE.schedule(this::expire, 6, TimeUnit.SECONDS);
        Future<InetAddress> lookup = null;
        try {
            lookup = DNS.submit(() -> InetAddress.getByName(endpoint.host));
            InetAddress address = lookup.get(1500, TimeUnit.MILLISECONDS);
            if (inactive()) return;
            // Serialize behind an old renderer's teardown and before its replacement.
            MonitorSessionGate.acquire(0); ownsMonitor = true;
            if (inactive()) return;
            SSLSocket socket = factory.open(pin);
            synchronized (this) {
                closing = new SocketShutdown(socket, "MetuxaProbeClose");
                if (inactive()) { closing.request(); return; }
            }
            socket.setTcpNoDelay(true); socket.setSoTimeout(1500);
            socket.connect(new InetSocketAddress(address, endpoint.port), 1500); socket.startHandshake();
            if (!inactive()) size = readMetadata(socket, token);
        } catch (Exception ignored) {
            // A probe failure only chooses fallback bounds. Normal pinned streaming
            // still performs its own authentication and reports the precise failure.
        } finally {
            if (lookup != null) { lookup.cancel(true); DNS.purge(); }
            SocketShutdown close;
            synchronized (this) { close = closing; }
            if (close != null) close.await(); // Never launch while this peer still owns TLS.
            if (ownsMonitor) MonitorSessionGate.release(0);
            timeout.cancel(false);
            final int[] result = inactive() ? null : size;
            main.post(() -> { synchronized (StreamMetadataProbe.this) { if (cancelled) return; } listener.complete(result); });
        }
    }
    static int[] readMetadata(SSLSocket socket, String token) throws Exception {
        SecureTransport.unhex(token, 16);
        socket.getOutputStream().write(("AUTH " + token + "\n").getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
        return parseHello(new SecureTransport.LineReader(socket.getInputStream()).readLine());
    }
    static int[] parseHello(String line) throws Exception {
        if (line == null || !line.startsWith("HELLO ")) throw new IOException("Missing authenticated metadata");
        JSONObject hello = new JSONObject(line.substring(6));
        if (hello.getInt("version") != 5) throw new IOException("Unsupported secure protocol");
        JSONObject video = hello.getJSONObject("video"); String codec = video.getString("codec");
        int width = video.getInt("width"), height = video.getInt("height");
        if ((!"h264".equals(codec) && !"h265".equals(codec)) || width < 16 || width > 8192 || height < 16 || height > 8192)
            throw new IOException("Invalid display dimensions");
        return new int[] {width, height};
    }
}
