package com.metashare.client;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;

/** A TLS close may write close_notify. It must never run on an Activity thread. */
final class SocketShutdown {
    private final Socket socket;
    private final String threadName;
    private final CountDownLatch finished = new CountDownLatch(1);
    private boolean requested;

    SocketShutdown(Socket socket, String threadName) {
        this.socket = socket;
        this.threadName = threadName;
    }

    /** Nonblocking and idempotent, including while the provider's close is blocked. */
    synchronized void request() {
        if (requested) return;
        requested = true;
        Thread closer = new Thread(() -> {
            try { socket.close(); }
            catch (IOException ignored) { }
            finally { finished.countDown(); }
        }, threadName);
        closer.setDaemon(true);
        closer.start();
    }

    /** Background session cleanup only. Do not release monitor ownership before this. */
    void await() {
        request();
        boolean interrupted = false;
        for (;;) {
            try { finished.await(); break; }
            catch (InterruptedException stopped) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
