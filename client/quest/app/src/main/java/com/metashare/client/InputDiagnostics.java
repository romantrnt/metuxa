package com.metashare.client;

import java.util.ArrayDeque;
import java.util.Locale;

/** Volatile opt-in metadata only. No pointer coordinates, key events, text or network data. */
final class InputDiagnostics {
    static final int MAX_ENTRIES = 80;
    private final ArrayDeque<String> entries = new ArrayDeque<>();
    private boolean enabled;
    private long lastRecorded = -1, seen, sampled;
    synchronized void setEnabled(boolean enabled) { this.enabled = enabled; if (!enabled) reset(); }
    synchronized boolean enabled() { return enabled; }
    synchronized void record(long now, int source, int tool, int device, int action, int buttons) {
        if (!enabled) return;
        seen++;
        if (lastRecorded >= 0 && now - lastRecorded < 100) return;
        lastRecorded = now; sampled++;
        String line = String.format(Locale.ROOT, "source=0x%08x  tool=%d  device=%d  action=%d  buttons=0x%x", source, tool, device, action, buttons);
        if (entries.size() == MAX_ENTRIES) entries.removeFirst();
        entries.addLast(line);
    }
    synchronized String snapshot() {
        StringBuilder text = new StringBuilder("Pointer events observed: " + seen + "; sampled: " + sampled + "\nAt most 10 samples/sec; last 80, memory only.\n\n");
        for (String e : entries) text.append(e).append('\n');
        return text.toString();
    }
    synchronized void reset() { entries.clear(); seen = sampled = 0; lastRecorded = -1; }
}
