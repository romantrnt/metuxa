package com.metashare.client;

import android.os.NetworkOnMainThreadException;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Exercises the production Activity-facing stop path without a real network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class StreamShutdownTest {
    private static StreamSession session() {
        return new StreamSession(RuntimeEnvironment.getApplication(), null, 0, new StreamSession.Listener() {
            public void onStatus(String text) { }
            public void onStreamSize(int width, int height) { }
            public void onAvailableMonitors(int count) { }
        });
    }

    @Test public void stoppingAnActiveTlsSessionNeverClosesOnTheActivityThread() throws Exception {
        Thread activityThread = Thread.currentThread();
        CountDownLatch entered = new CountDownLatch(1), allowClose = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Socket socket = new Socket() {
            @Override public void close() {
                closes.incrementAndGet();
                if (Thread.currentThread() == activityThread) {
                    failure.set(new NetworkOnMainThreadException());
                    throw new NetworkOnMainThreadException();
                }
                entered.countDown();
                try { if (!allowClose.await(4, TimeUnit.SECONDS)) failure.set(new AssertionError("UI stop waited for TLS close")); }
                catch (InterruptedException error) { failure.set(error); Thread.currentThread().interrupt(); }
            }
        };
        StreamSession session = session();
        ReflectionHelpers.setField(session, "signalingSocket", new SocketShutdown(socket, "TlsCloseRegression"));
        Thread cleanup = new Thread(() -> {
            try { ReflectionHelpers.callInstanceMethod(session, "closeSignalingSocket"); }
            catch (Throwable error) { failure.set(error); }
            finally { cleaned.countDown(); }
        }, "SessionCleanupRegression");
        try {
            session.stop();
            session.stop();
            assertTrue("Close runs independently of Activity stop", entered.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            cleanup.start();
            assertFalse("Worker retains socket ownership while TLS close is pending", cleaned.await(50, TimeUnit.MILLISECONDS));
        } finally {
            allowClose.countDown();
            if (cleanup.getState() == Thread.State.NEW) cleanup.start();
            cleanup.join(3000);
        }
        assertFalse("Cleanup terminates", cleanup.isAlive());
        assertEquals("Repeated stop closes exactly once", 1, closes.get());
        assertNull(failure.get());
    }

    @Test public void deferredOldCloseCannotCloseAReplacementSocket() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), allowClose = new CountDownLatch(1);
        AtomicInteger oldCloses = new AtomicInteger(), newCloses = new AtomicInteger();
        SocketShutdown old = new SocketShutdown(new Socket() {
            @Override public void close() {
                oldCloses.incrementAndGet(); entered.countDown();
                try { allowClose.await(4, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        }, "OldSocketRegression");
        SocketShutdown replacement = new SocketShutdown(new Socket() {
            @Override public void close() { newCloses.incrementAndGet(); }
        }, "ReplacementSocketRegression");
        StreamSession session = session();
        ReflectionHelpers.setField(session, "signalingSocket", old);
        try {
            session.stop();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            ReflectionHelpers.setField(session, "signalingSocket", replacement);
        } finally { allowClose.countDown(); }
        old.await();
        assertEquals(1, oldCloses.get());
        assertEquals("Old close captured its original socket", 0, newCloses.get());
        replacement.await();
    }
}
