package com.metashare.client;

import java.util.LinkedHashSet;
import java.util.Objects;

/** One remote drag has one Android source owner; other sources cannot mutate it. */
final class PointerOwnership {
    static final int IGNORE = 0, FORWARD = 1, RELEASE_OWNER = 2;
    private static final int MAX_ARMED_SOURCES = 32;
    private static final class Source {
        final int device;
        final String descriptor;
        Source(int device, String descriptor) { this.device = device; this.descriptor = descriptor == null ? "" : descriptor; }
        @Override public boolean equals(Object other) {
            return other instanceof Source && device == ((Source) other).device && descriptor.equals(((Source) other).descriptor);
        }
        @Override public int hashCode() { return Objects.hash(device, descriptor); }
    }
    private final LinkedHashSet<Source> armed = new LinkedHashSet<>();
    private Source owner;
    private boolean contact;

    int gate(int device, String descriptor, boolean allowed, int action, int buttons) {
        Source source = new Source(device, descriptor);
        if (!allowed) {
            // A denied hand/controller is not a cancellation of an allowed mouse.
            if (ownsDevice(device)) { reset(); return RELEASE_OWNER; }
            return IGNORE;
        }
        if (owner != null) {
            if (owner.device != device) return IGNORE;
            if (!owner.equals(source)) { reset(); return RELEASE_OWNER; }
            return FORWARD;
        }
        if (!armed.contains(source)) {
            // A different device's neutral hover cannot rearm this device. In
            // particular, LEFT held across reset + fresh RIGHT(mask=3) stays blocked.
            if (buttons != 0 || action == 2 || action == 5 || action == 6 || action == 11) return IGNORE;
            if (armed.size() == MAX_ARMED_SOURCES) armed.remove(armed.iterator().next());
            armed.add(source);
        }
        // A press ignored during another source's drag cannot reappear later
        // merely because that source sends a pressed MOVE/SCROLL tail.
        if (buttons != 0 && action != 0 && action != 11) return IGNORE;
        return FORWARD;
    }
    void forwarded(int device, String descriptor, int action, int buttons) {
        if (action == 1 || action == 3 || action == 10) { owner = null; contact = false; return; }
        // Ownership tracks the physical gesture, not permission to inject buttons.
        // InputState independently gates click output; movement/scroll must continue.
        if (action == 0) {
            owner = new Source(device, descriptor); contact = buttons == 0;
        } else if (action == 11 && buttons != 0 && owner == null) {
            owner = new Source(device, descriptor); contact = false;
        }
        if (owner != null && !contact && buttons == 0) owner = null;
    }
    boolean ownsDevice(int device) { return owner != null && owner.device == device; }
    boolean matchesOwner(int device, String descriptor) { return owner != null && owner.equals(new Source(device, descriptor)); }
    boolean removed(int device) {
        armed.removeIf(source -> source.device == device);
        if (!ownsDevice(device)) return false;
        reset(); return true;
    }
    void reset() { owner = null; contact = false; armed.clear(); }
}
