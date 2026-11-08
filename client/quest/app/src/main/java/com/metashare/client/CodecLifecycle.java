package com.metashare.client;

import android.os.Handler;
import android.os.HandlerThread;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** One codec owner thread, including initialization, callbacks and final cleanup. */
final class CodecLifecycle {
    interface Operation { void run() throws Exception; }
    private final String name;
    private final Object lock = new Object();
    private final CountDownLatch terminated = new CountDownLatch(1);
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean closed;

    CodecLifecycle(String name) { this.name = name; }
    boolean isClosed() { return closed; }
    Handler handler() { return handler; }

    /** Called only by a background session worker. A stop cannot abandon initialization. */
    void initialize(Operation operation) throws Exception {
        CountDownLatch initialized = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        synchronized (lock) {
            if (closed || thread != null) throw new IllegalStateException("Decoder already initialized or closed");
            thread = new HandlerThread(name);
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.post(() -> {
                try {
                    if (closed) throw new IllegalStateException("Decoder closed during initialization");
                    operation.run();
                } catch (Exception error) { failure.set(error); }
                finally { initialized.countDown(); }
            });
        }
        await(initialized);
        if (failure.get() != null) throw failure.get();
        if (closed) throw new IllegalStateException("Decoder closed during initialization");
    }

    boolean post(Runnable operation) { return postDelayed(operation, 0); }
    boolean postDelayed(Runnable operation, long delayMs) {
        synchronized (lock) {
            return !closed && handler != null && handler.postDelayed(() -> {
                if (!closed) operation.run();
            }, delayMs);
        }
    }

    /**
     * Close admission immediately, then serialize cleanup behind an in-flight callback.
     * Never wait on our own callback thread. Session workers wait uninterruptibly so
     * a replacement cannot use the same monitor's media resources before teardown.
     */
    void close(Runnable cleanup) {
        boolean ownerThread;
        boolean runHere = false;
        synchronized (lock) {
            ownerThread = Thread.currentThread() == thread;
            if (!closed) {
                closed = true;
                if (handler == null || ownerThread) runHere = true;
                else handler.post(() -> finish(cleanup));
            }
        }
        // Do not remove initialization's completion or another close's barrier.
        // Every queued operation/callback checks the closed gate instead.
        if (runHere) finish(cleanup);
        if (!ownerThread) await(terminated);
    }

    private void finish(Runnable cleanup) {
        try { cleanup.run(); }
        finally {
            if (handler != null) handler.removeCallbacksAndMessages(null);
            if (thread != null) thread.quitSafely();
            terminated.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try { latch.await(); break; }
            catch (InterruptedException stopped) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
