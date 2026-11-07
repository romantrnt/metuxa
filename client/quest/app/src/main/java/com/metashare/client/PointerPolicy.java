package com.metashare.client;

import java.util.Set;

/** Device filtering is a best-effort Android identity allowlist, not hand detection. */
final class PointerPolicy {
    static final int FULL = 0, BLOCK = 1, DEVICES = 2;
    static boolean permits(int mode, boolean physical, String descriptor, Set<String> allowed) {
        if (mode == BLOCK) return false;
        if (mode == DEVICES) return physical && descriptor != null && !descriptor.isEmpty() && allowed.contains(descriptor);
        return mode == FULL;
    }
    static String label(int mode) {
        if (mode == BLOCK) return "All remote pointer input blocked";
        if (mode == DEVICES) return "Selected Android pointer devices only";
        return "Remote pointer enabled";
    }
    private PointerPolicy() {}
}
