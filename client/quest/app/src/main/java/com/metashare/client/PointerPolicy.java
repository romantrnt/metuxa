package com.metashare.client;

import java.util.Set;

/** Device filtering is a best-effort Android identity allowlist, not hand detection. */
final class PointerPolicy {
    static final int FULL = 0, BLOCK = 1, DEVICES = 2, EXCEPT_DEVICES = 3;
    static boolean permits(int mode, boolean physical, String descriptor, Set<String> allowed) {
        if (mode == BLOCK) return false;
        if (mode == DEVICES || mode == EXCEPT_DEVICES) {
            // Both filters require an actual Android-identifiable non-virtual device.
            // This is deliberately stricter than an unfiltered "all except" rule.
            if (!physical || descriptor == null || descriptor.isEmpty()) return false;
            return mode == DEVICES ? allowed.contains(descriptor) : !allowed.contains(descriptor);
        }
        return mode == FULL;
    }
    static String label(int mode) {
        if (mode == BLOCK) return "All remote pointer input blocked";
        if (mode == DEVICES) return "Selected Android pointer devices only";
        if (mode == EXCEPT_DEVICES) return "Identifiable physical pointer devices except excluded devices";
        return "Remote pointer enabled";
    }
    private PointerPolicy() {}
}
