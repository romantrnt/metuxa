package com.metashare.client;

import java.util.HashSet;
import java.util.Set;

/** Per-scan bounds apply before parsing or retaining untrusted LAN replies. */
final class DiscoveryBudget {
    static final int MAX_RESPONSES = 256;
    static final int MAX_HOSTS = 32;
    private final Set<String> seen = new HashSet<>();
    private int responses;

    boolean hasCapacity() { return responses < MAX_RESPONSES && seen.size() < MAX_HOSTS; }
    boolean consumeResponse() {
        if (!hasCapacity()) return false;
        responses++;
        return true;
    }
    boolean addHost(String key) {
        // Check the cap before insertion, including when a caller is saturated.
        return seen.size() < MAX_HOSTS && seen.add(key);
    }
    int hostCount() { return seen.size(); }
    int responseCount() { return responses; }
}
