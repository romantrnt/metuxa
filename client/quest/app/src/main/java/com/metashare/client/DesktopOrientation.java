package com.metashare.client;

/** Bounded, one-request control state. No Android, network I/O or pixel transforms. */
final class DesktopOrientation {
    private boolean supported, pending;
    private String queued;
    private long sentAt;
    synchronized void open(String source, boolean capability) {
        close(); supported = "virtual".equals(source) && capability;
    }
    synchronized void close() { supported = false; pending = false; queued = null; sentAt = 0; }
    synchronized boolean request(boolean portrait) {
        if (!supported || pending) return false;
        queued = portrait ? "portrait" : "landscape"; pending = true; return true;
    }
    synchronized String claim(long now) {
        if (queued == null) return null;
        String result = "DISPLAY_ORIENTATION " + queued; queued = null; sentAt = now; return result;
    }
    synchronized boolean pending() { return pending; }
    synchronized boolean awaitingResult() { return pending && queued == null; }
    synchronized boolean expired(long now) { return pending && queued == null && now - sentAt >= 12_000_000_000L; }
    synchronized void complete() { pending = false; queued = null; sentAt = 0; }
    static final class Result {
        final String status; final int width, height;
        Result(String status, int width, int height) { this.status = status; this.width = width; this.height = height; }
    }
    static Result parse(String line) {
        String[] parts = line.split(" ", -1);
        if (parts.length < 2 || !"DISPLAY_RESULT".equals(parts[0])) throw new IllegalArgumentException("Invalid display result");
        if (parts.length == 2 && ("unsupported".equals(parts[1]) || "failed".equals(parts[1]) || "restart_required".equals(parts[1]) || "busy".equals(parts[1])))
            return new Result(parts[1], 0, 0);
        if (parts.length != 4 || !"ok".equals(parts[1]) || !parts[2].matches("[0-9]{2,4}") || !parts[3].matches("[0-9]{2,4}"))
            throw new IllegalArgumentException("Invalid display result");
        int width = Integer.parseInt(parts[2]), height = Integer.parseInt(parts[3]);
        if (width < 16 || height < 16 || width > 8192 || height > 8192) throw new IllegalArgumentException("Invalid display size");
        return new Result("ok", width, height);
    }
}
