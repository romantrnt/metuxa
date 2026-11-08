package com.metashare.client;

import android.os.Looper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Fake TLS socket only: no connection, media, capture or discovery can occur. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34) @LooperMode(LooperMode.Mode.PAUSED)
public class StreamMetadataProbeTest {
    private static final String HELLO = "HELLO {\"version\":5,\"video\":{\"codec\":\"h264\",\"width\":1200,\"height\":1920}}\n";
    @Test public void acceptsOnlyBoundedCompatibleMetadata() throws Exception {
        assertArrayEquals(new int[] {1200, 1920}, StreamMetadataProbe.parseHello(HELLO.trim()));
        for (String invalid : new String[] {"BYE", "HELLO {}", HELLO.replace("1200", "0"), HELLO.replace("1920", "8193"), HELLO.replace("h264", "untrusted"), HELLO.replace("\"version\":5", "\"version\":4")}) {
            boolean rejected = false; try { StreamMetadataProbe.parseHello(invalid.trim()); } catch (Exception expected) { rejected = true; } assertTrue(rejected);
        }
    }
    @Test public void closesBeforeCallbackAndSendsAuthOnly() throws Exception {
        FakeSocket socket = new FakeSocket(); Thread caller = Thread.currentThread(); AtomicInteger calls = new AtomicInteger(); AtomicReference<int[]> result = new AtomicReference<>();
        StreamMetadataProbe probe = new StreamMetadataProbe(pin -> { assertEquals("ab".repeat(32), pin); return socket; });
        StreamMetadataProbe.Listener listener = size -> { assertTrue(socket.closed); calls.incrementAndGet(); result.set(size); };
        probe.start(new HostEndpoint("127.0.0.1", 7878), "ab".repeat(32), "cd".repeat(16), listener);
        probe.start(new HostEndpoint("127.0.0.1", 7878), "ab".repeat(32), "cd".repeat(16), listener);
        join(probe); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, calls.get()); assertArrayEquals(new int[] {1200, 1920}, result.get());
        assertEquals("AUTH " + "cd".repeat(16) + "\n", socket.output.toString(StandardCharsets.US_ASCII));
        assertNotSame(caller, socket.closeThread); assertEquals(1, socket.handshakes);
    }
    @Test public void cancellationSuppressesCallbackAndExpiryFallsBackAfterClose() throws Exception {
        for (boolean cancel : new boolean[] {true, false}) {
            FakeSocket socket = new FakeSocket(); socket.holdClose = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
            StreamMetadataProbe probe = new StreamMetadataProbe(pin -> socket);
            probe.start(new HostEndpoint("127.0.0.1", 7878), "ab".repeat(32), "cd".repeat(16), size -> { assertTrue(socket.closed); assertNull(size); calls.incrementAndGet(); });
            assertTrue(socket.closeEntered.await(2, TimeUnit.SECONDS));
            if (cancel) probe.cancel(); else ReflectionHelpers.callInstanceMethod(probe, "expire");
            Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, calls.get());
            socket.holdClose.countDown(); join(probe); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(cancel ? 0 : 1, calls.get());
        }
    }
    private static void join(StreamMetadataProbe probe) throws Exception {
        Thread worker = ReflectionHelpers.getField(probe, "worker"); worker.join(3000); assertFalse("Probe worker terminated", worker.isAlive());
    }
    private static final class FakeSocket extends SSLSocket {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(); final CountDownLatch closeEntered = new CountDownLatch(1);
        volatile boolean closed; volatile Thread closeThread; volatile CountDownLatch holdClose; int handshakes;
        @Override public void connect(SocketAddress endpoint, int timeout) { assertTrue(timeout > 0 && timeout <= 1500); }
        @Override public void startHandshake() { handshakes++; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(HELLO.getBytes(StandardCharsets.US_ASCII)); }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void close() { closeThread = Thread.currentThread(); closeEntered.countDown(); try { if (holdClose != null) assertTrue(holdClose.await(3, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } closed = true; }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public String[] getEnabledCipherSuites() { return new String[0]; }
        @Override public void setEnabledCipherSuites(String[] suites) { }
        @Override public String[] getSupportedProtocols() { return new String[0]; }
        @Override public String[] getEnabledProtocols() { return new String[0]; }
        @Override public void setEnabledProtocols(String[] protocols) { }
        @Override public SSLSession getSession() { return null; }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override public void setUseClientMode(boolean mode) { }
        @Override public boolean getUseClientMode() { return true; }
        @Override public void setNeedClientAuth(boolean need) { }
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean want) { }
        @Override public boolean getWantClientAuth() { return false; }
        @Override public void setEnableSessionCreation(boolean flag) { }
        @Override public boolean getEnableSessionCreation() { return true; }
    }
}
