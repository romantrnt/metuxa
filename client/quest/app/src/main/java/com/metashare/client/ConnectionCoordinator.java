package com.metashare.client;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Process-scoped explicit connection. Recreated or foreign panels cannot choose another PC. */
final class ConnectionCoordinator {
    interface Panel { void disconnectPanel(); void policyChanged(); }
    static final ConnectionCoordinator INSTANCE = new ConnectionCoordinator();
    final InputDiagnostics diagnostics = new InputDiagnostics();
    private final List<Panel> panels = new ArrayList<>();
    private long generation;
    private Connection connection;
    String lastStatus = "Choose a PC to begin.";
    static final class Connection {
        final long generation;
        final HostEndpoint endpoint;
        final String pin, token;
        final boolean audio;
        int desiredMonitors;
        int availableMonitors = 1;
        boolean connected, primaryLaunched;
        Connection(long generation, HostEndpoint endpoint, String pin, String token, boolean audio, int count) {
            this.generation = generation; this.endpoint = endpoint; this.pin = pin; this.token = token; this.audio = audio;
            desiredMonitors = Math.max(1, Math.min(3, count));
        }
    }
    Connection connect(HostEndpoint endpoint, String pin, String token, boolean audio, int count) {
        disconnect();
        connection = new Connection(++generation, endpoint, pin, token, audio, count);
        lastStatus = "Connecting to " + endpoint.key(); return connection;
    }
    Connection current() { return connection; }
    boolean matches(long id) { return connection != null && connection.generation == id; }
    void register(Panel panel) { if (!panels.contains(panel)) panels.add(panel); }
    void unregister(Panel panel) { panels.remove(panel); }
    void disconnect() {
        connection = null; generation++;
        for (Panel panel : new ArrayList<>(panels)) panel.disconnectPanel();
        panels.clear(); lastStatus = "Disconnected. Choose Connect when you are ready.";
    }
    void changePointerMode(Context context, int mode) {
        AppSettings settings = new AppSettings(context);
        if (mode != PointerPolicy.BLOCK) settings.preferences.edit().putInt("previous_pointer_mode", mode).apply();
        settings.pointerMode(mode);
        notifyPolicy();
    }
    void changeInputOptions(Context context, InputOptions options) {
        AppSettings settings = new AppSettings(context); settings.inputOptions(options);
        if (options.pointerMode != PointerPolicy.BLOCK) settings.preferences.edit().putInt("previous_pointer_mode", options.pointerMode).apply();
        notifyPolicy();
    }
    void togglePointer(Context context) {
        AppSettings settings = new AppSettings(context);
        int next = settings.pointerMode() == PointerPolicy.BLOCK
                ? settings.preferences.getInt("previous_pointer_mode", PointerPolicy.FULL) : PointerPolicy.BLOCK;
        changePointerMode(context, next == PointerPolicy.BLOCK ? next : next == PointerPolicy.DEVICES || next == PointerPolicy.EXCEPT_DEVICES ? next : PointerPolicy.FULL);
    }
    void notifyPolicy() { for (Panel p : new ArrayList<>(panels)) p.policyChanged(); }
}
