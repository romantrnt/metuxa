package com.metashare.client;

/** A renderer drops its owner reference on detach; decoder leases finish asynchronously. */
final class SurfaceLifetime {
    private final Runnable cleanup;
    private int references = 1;
    private boolean retired;
    SurfaceLifetime(Runnable cleanup) { this.cleanup = cleanup; }
    synchronized boolean retain() {
        if (retired) return false;
        references++; return true;
    }
    void retire() {
        boolean release;
        synchronized (this) {
            if (retired) return;
            retired = true; release = --references == 0;
        }
        if (release) cleanup.run();
    }
    void release() {
        boolean cleanupNow;
        synchronized (this) {
            if (references <= 0 || !retired && references == 1) throw new IllegalStateException("No decoder surface lease");
            cleanupNow = --references == 0;
        }
        if (cleanupNow) cleanup.run();
    }
}
