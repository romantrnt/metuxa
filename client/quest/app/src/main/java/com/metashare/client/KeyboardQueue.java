package com.metashare.client;

import java.util.ArrayDeque;
import java.util.ArrayList;

/** Bounded ordered commits: never truncate an IME paste or let Enter overtake it. */
final class KeyboardQueue {
    static final int CAPACITY = 4096;
    interface Sink { void character(int codePoint); void key(int keysym, boolean down); }
    private static final class Operation {
        final int value; final boolean character, down;
        Operation(int value, boolean character, boolean down) { this.value = value; this.character = character; this.down = down; }
    }
    private final ArrayDeque<Operation> pending = new ArrayDeque<>();
    boolean text(String text) {
        if (text == null || text.length() > CAPACITY * 2) return false;
        ArrayList<Operation> next = new ArrayList<>();
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i); i += Character.charCount(cp);
            if (cp >= 0xd800 && cp <= 0xdfff || cp < 32 && cp != '\n' && cp != '\t') continue;
            next.add(new Operation(cp, true, false));
            if (next.size() + pending.size() > CAPACITY) return false;
        }
        pending.addAll(next); return true;
    }
    boolean key(int keysym, boolean down) {
        if (pending.size() == CAPACITY) return false;
        pending.addLast(new Operation(keysym, false, down)); return true;
    }
    int drain(int budget, Sink sink) {
        int sent = 0;
        while (sent < budget && !pending.isEmpty()) {
            Operation o = pending.removeFirst();
            if (o.character) sink.character(o.value); else sink.key(o.value, o.down);
            sent++;
        }
        return sent;
    }
    int size() { return pending.size(); }
    void clear() { pending.clear(); }
}
