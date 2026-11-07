package com.metashare.client;

import java.util.Locale;

/** A selected endpoint, never an instruction to connect to any discovered PC. */
final class HostEndpoint {
    final String host;
    final int port;
    HostEndpoint(String address, int port) {
        String host = address == null ? "" : address.trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty() || host.length() > 253 || !host.matches("[A-Za-z0-9][A-Za-z0-9.:%_-]*")
                || host.contains("..") || host.endsWith("-")) {
            throw new IllegalArgumentException("Enter a hostname or IP address, without a URL or port.");
        }
        if (port < 1 || port > 65533) throw new IllegalArgumentException("Base port must be 1–65533 (three monitor ports are reserved).");
        if (host.contains(":")) {
            try { new java.net.URI("metuxa://[" + host + "]:" + port); }
            catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("Enter an IPv6 address, or put the port in the separate Base port field."); }
        }
        this.host = host.toLowerCase(Locale.ROOT);
        this.port = port;
    }
    static HostEndpoint parse(String host, String port) {
        try { return new HostEndpoint(host, Integer.parseInt(port.trim())); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Enter a numeric base port, usually 7878."); }
    }
    String key() { return (host.contains(":") ? "[" + host + "]" : host) + ":" + port; }
    static String fingerprint(String value) {
        String normalized = value == null ? "" : value.replace(":", "").replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Paste the server's full SHA-256 fingerprint (64 hexadecimal characters).");
        return normalized;
    }
    static String token(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Enter the server's pairing token (32 hexadecimal characters).");
        return normalized;
    }
    @Override public boolean equals(Object o) { return o instanceof HostEndpoint && key().equals(((HostEndpoint) o).key()); }
    @Override public int hashCode() { return key().hashCode(); }
    @Override public String toString() { return key(); }
}
