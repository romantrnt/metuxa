package com.metashare.client;

import java.util.ArrayDeque;

/**
 * Bounded input queue with a non-blocking policy-change boundary.
 *
 * <p>Only the single input sender may take and claim items. It must claim after
 * acquiring the socket write lock, immediately before writing. A policy change
 * invalidates every old item that has not crossed this claim boundary, including
 * an item already dequeued while waiting for the write lock. A claimed write
 * cannot be recalled: release-all is ordered behind it and ahead of all new
 * input. No method here performs or waits for network I/O.
 */
final class SessionInputQueue {
    static final class Session {}

    static final class Pending {
        private final Session session;
        private final Object generation;
        private final String line;
        private boolean claimed;

        private Pending(Session session, Object generation, String line) {
            this.session = session;
            this.generation = generation;
            this.line = line;
        }
    }

    private final int capacity;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private Session session;
    private Object generation = new Object();
    private boolean enabled;

    SessionInputQueue(int capacity) {
        if (capacity < 2) throw new IllegalArgumentException("Input capacity must be at least two");
        this.capacity = capacity;
    }

    synchronized Session open(boolean enabled) {
        session = new Session();
        generation = new Object();
        queue.clear();
        this.enabled = enabled;
        notifyAll();
        return session;
    }

    synchronized void close() {
        session = null;
        generation = new Object();
        queue.clear();
        enabled = false;
        notifyAll();
    }

    synchronized void offer(String body) {
        if (session == null || !enabled || body == null || body.isEmpty()) return;
        if (body.charAt(0) == 'm' && !queue.isEmpty()
                && queue.peekLast().line.startsWith("INPUT m ")) queue.pollLast();
        if (queue.size() >= capacity) resetWithRelease();
        queue.addLast(new Pending(session, generation, "INPUT " + body));
        notifyAll();
    }

    synchronized void releaseAll() {
        // An explicit policy change invalidates dequeued items even while the
        // server gate is off. Release remains ahead of a later re-enable.
        resetWithRelease();
        notifyAll();
    }

    synchronized boolean setEnabled(Session owner, boolean value) {
        if (session != owner || session == null || enabled == value) return false;
        enabled = value;
        if (!value) resetWithRelease();
        notifyAll();
        return true;
    }

    synchronized Pending take(Session owner) throws InterruptedException {
        while (session == owner && session != null && queue.isEmpty()) wait();
        if (session != owner || session == null) return null;
        return queue.removeFirst();
    }

    synchronized String claim(Session owner, Pending item) {
        if (item == null || item.claimed || session == null || session != owner
                || item.session != owner || item.generation != generation) return null;
        item.claimed = true;
        return item.line;
    }

    private void resetWithRelease() {
        generation = new Object();
        queue.clear();
        if (session != null) queue.addLast(new Pending(session, generation, "INPUT r"));
    }
}
