package com.metashare.client;

import java.util.HashMap;
import java.util.Map;

/** Tracks remotely pressed hardware keys independently of the current local View focus. */
final class HardwareKeyLedger {
    private final Map<Integer, Integer> keys = new HashMap<>();
    boolean press(int code, int keysym) {
        if (keys.containsKey(code)) return false;
        keys.put(code, keysym); return true;
    }
    boolean contains(int code) { return keys.containsKey(code); }
    Integer release(int code) { return keys.remove(code); }
    void clear() { keys.clear(); }
}
