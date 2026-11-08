package com.metashare.client;

import java.util.concurrent.Semaphore;

/** Media ownership survives Activity recreation; waiting never blocks the UI thread. */
final class MonitorSessionGate {
    private static final Semaphore[] MONITORS = new Semaphore[16];
    static { for (int i = 0; i < MONITORS.length; ++i) MONITORS[i] = new Semaphore(1, true); }
    static void acquire(int monitor) throws InterruptedException { MONITORS[monitor].acquire(); }
    static void release(int monitor) { MONITORS[monitor].release(); }
    private MonitorSessionGate() {}
}
