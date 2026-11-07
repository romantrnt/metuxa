package com.metashare.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SessionInputQueueTest {
    private static int assertions;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        assertions++;
    }
    private static String next(SessionInputQueue queue, SessionInputQueue.Session session) throws Exception {
        return queue.claim(session, queue.take(session));
    }
    private static void await(CountDownLatch latch, String message) throws Exception {
        check(latch.await(2, TimeUnit.SECONDS), message);
    }
    private static void join(Thread thread) throws Exception {
        thread.join(2000);
        check(!thread.isAlive(), "worker terminates");
    }
    private static void basicOrdering() throws Exception {
        SessionInputQueue queue = new SessionInputQueue(4);
        SessionInputQueue.Session session = queue.open(true);
        queue.offer("k 65 1");
        SessionInputQueue.Pending old = queue.take(session);
        queue.releaseAll();
        queue.offer("k 66 1");
        check(queue.claim(session, old) == null, "release invalidates dequeued unclaimed item");
        check("INPUT r".equals(next(queue, session)), "release comes first");
        SessionInputQueue.Pending fresh = queue.take(session);
        check("INPUT k 66 1".equals(queue.claim(session, fresh)), "new generation follows release");
        check(queue.claim(session, fresh) == null, "an item can only be claimed once");

        queue.offer("k 65 1");
        old = queue.take(session);
        check("INPUT k 65 1".equals(queue.claim(session, old)), "claim is the in-flight boundary");
        queue.releaseAll();
        queue.offer("k 67 1");
        check("INPUT r".equals(next(queue, session)), "release follows claimed write");
        check("INPUT k 67 1".equals(next(queue, session)), "new input follows in-flight repair");
        queue.close();
    }
    private static void gateAndOverflow() throws Exception {
        SessionInputQueue queue = new SessionInputQueue(2);
        SessionInputQueue.Session session = queue.open(true);
        queue.offer("k 65 1");
        SessionInputQueue.Pending old = queue.take(session);
        check(queue.setEnabled(session, false), "gate off changes state");
        queue.offer("k 66 1");
        check(queue.claim(session, old) == null, "gate off invalidates dequeued item");
        check(!queue.setEnabled(session, false), "repeated gate off is stable");
        check(queue.setEnabled(session, true), "gate can re-enable");
        queue.offer("k 67 1");
        check("INPUT r".equals(next(queue, session)), "gate release survives re-enable");
        check("INPUT k 67 1".equals(next(queue, session)), "disabled-generation input is absent");

        queue.offer("k 68 1");
        old = queue.take(session);
        queue.offer("k 69 1"); queue.offer("k 70 1"); queue.offer("k 71 1");
        check(queue.claim(session, old) == null, "overflow invalidates dequeued item");
        check("INPUT r".equals(next(queue, session)), "overflow releases held input");
        check("INPUT k 71 1".equals(next(queue, session)), "overflow retains only newest event after release");
        queue.offer("m 0.1 0.1"); queue.offer("m 0.2 0.2");
        check("INPUT m 0.2 0.2".equals(next(queue, session)), "pointer coalescing retained");
        queue.close();
    }
    private static void generationsAndSessions() throws Exception {
        SessionInputQueue queue = new SessionInputQueue(4);
        SessionInputQueue.Session first = queue.open(true);
        queue.releaseAll();
        SessionInputQueue.Pending oldRelease = queue.take(first);
        queue.releaseAll(); queue.offer("k 65 1");
        check(queue.claim(first, oldRelease) == null, "new policy also invalidates old release candidate");
        check("INPUT r".equals(next(queue, first)), "newest release is retained");
        SessionInputQueue.Pending old = queue.take(first);
        queue.close();
        check(queue.claim(first, old) == null, "close invalidates old candidates");
        SessionInputQueue.Session second = queue.open(true);
        queue.offer("k 66 1");
        check(queue.take(first) == null, "old sender cannot drain new session");
        check(!queue.setEnabled(first, false), "old gate update cannot alter new session");
        check("INPUT k 66 1".equals(next(queue, second)), "new sender owns new session");
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            try { if (queue.take(second) != null) throw new AssertionError("closed wait returned input"); }
            catch (Throwable e) { failure.set(e); }
        });
        waiter.start(); await(waiting, "waiter started"); queue.close(); join(waiter);
        check(failure.get() == null, "close wakes idle sender");
    }
    private static void dequeuedBeforeWriteLock() throws Exception {
        SessionInputQueue queue = new SessionInputQueue(4);
        SessionInputQueue.Session session = queue.open(true);
        queue.offer("k 65 1");
        Object writeLock = new Object();
        CountDownLatch dequeued = new CountDownLatch(1);
        AtomicReference<String> sent = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sender;
        synchronized (writeLock) {
            sender = new Thread(() -> {
                try {
                    SessionInputQueue.Pending item = queue.take(session);
                    dequeued.countDown();
                    synchronized (writeLock) { sent.set(queue.claim(session, item)); }
                } catch (Throwable e) { failure.set(e); }
            });
            sender.start(); await(dequeued, "sender dequeued before socket lock");
            queue.releaseAll(); queue.offer("k 66 1");
        }
        join(sender);
        check(failure.get() == null && sent.get() == null, "candidate waiting for write lock is cancelled");
        check("INPUT r".equals(next(queue, session)), "release remains queued after cancellation");
        check("INPUT k 66 1".equals(next(queue, session)), "new event ordering preserved");
        queue.close();
    }
    private static void networkDoesNotBlockPolicyChange() throws Exception {
        SessionInputQueue queue = new SessionInputQueue(4);
        SessionInputQueue.Session session = queue.open(true);
        queue.offer("k 65 1");
        List<String> wire = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch networkBlocked = new CountDownLatch(1), finishWrite = new CountDownLatch(1);
        CountDownLatch policyDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sender = new Thread(() -> {
            try {
                String claimed = next(queue, session);
                networkBlocked.countDown();
                if (!finishWrite.await(2, TimeUnit.SECONDS)) throw new AssertionError("policy waited on network");
                wire.add(claimed);
                wire.add(next(queue, session)); wire.add(next(queue, session));
            } catch (Throwable e) { failure.set(e); }
        });
        sender.start(); await(networkBlocked, "claimed event in simulated network write");
        Thread policy = new Thread(() -> {
            queue.releaseAll(); queue.offer("k 66 1"); policyDone.countDown();
        });
        policy.start();
        try { await(policyDone, "UI policy change returns while socket write is blocked"); }
        finally { finishWrite.countDown(); }
        join(policy); join(sender);
        check(failure.get() == null, "network ordering worker succeeded");
        check(wire.equals(List.of("INPUT k 65 1", "INPUT r", "INPUT k 66 1")), "claimed event, release, new event wire ordering");
        queue.close();
    }
    public static void main(String[] args) throws Exception {
        basicOrdering(); gateAndOverflow(); generationsAndSessions();
        dequeuedBeforeWriteLock(); networkDoesNotBlockPolicyChange();
        System.out.println("SessionInputQueue: " + assertions + " assertions passed");
    }
}
