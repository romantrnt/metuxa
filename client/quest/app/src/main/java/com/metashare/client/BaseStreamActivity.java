package com.metashare.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Each panel has a renderer, but all panels belong to one explicitly selected session. */
abstract class BaseStreamActivity extends Activity implements SurfaceHolder.Callback, ConnectionCoordinator.Panel {
    private static final ArrayList<BaseStreamActivity> ACTIVE = new ArrayList<>();
    private static final Set<Integer> LAUNCHING = new HashSet<>();
    private final ConnectionCoordinator coordinator = ConnectionCoordinator.INSTANCE;
    private final SessionInputForwarder forwarder = new SessionInputForwarder();
    private ConnectionCoordinator.Connection connection;
    private RemoteSurfaceView surface;
    private FrameLayout viewport, root;
    private TextView status;
    private QuestToolbar toolbar;
    private RemoteInputController input;
    private StreamSession stream;
    private long generation, streamEpoch;
    private int monitorIndex, streamWidth, streamHeight;
    private boolean surfaceReady, destroyed, registered, inputAvailable;
    abstract boolean primary();
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        generation = getIntent().getLongExtra("generation", -1);
        monitorIndex = primary() ? 0 : getIntent().getIntExtra("monitor_index", -1);
        connection = coordinator.current();
        if (!coordinator.matches(generation) || monitorIndex < 0 || monitorIndex > 2 || !primary() && (monitorIndex == 0 || monitorIndex >= Math.min(connection.desiredMonitors, connection.availableMonitors))) { finish(); return; }
        for (BaseStreamActivity other : ACTIVE) if (other.generation == generation && other.monitorIndex == monitorIndex && !other.isFinishing()) { finish(); return; }
        registered = true; ACTIVE.add(this); LAUNCHING.remove(monitorIndex); coordinator.register(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root = new FrameLayout(this); root.setBackgroundColor(QuestUi.BACKGROUND); root.setFocusableInTouchMode(true);
        viewport = new FrameLayout(this); viewport.setBackgroundColor(android.graphics.Color.BLACK);
        FrameLayout.LayoutParams viewportParams = new FrameLayout.LayoutParams(-1, -1); viewportParams.topMargin = QuestUi.dp(this, 64); viewportParams.bottomMargin = QuestUi.dp(this, 68);
        root.addView(viewport, viewportParams);
        surface = new RemoteSurfaceView(this); surface.getHolder().addCallback(this); surface.setFocusableInTouchMode(true); surface.setContentDescription("Remote monitor " + (monitorIndex + 1) + " on " + connection.endpoint.key());
        viewport.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        status = QuestUi.text(this, "", 13, QuestUi.MUTED); status.setMaxLines(3); status.setPadding(QuestUi.dp(this, 16), QuestUi.dp(this, 6), QuestUi.dp(this, 16), 0);
        root.addView(status, new FrameLayout.LayoutParams(-1, QuestUi.dp(this, 64), Gravity.TOP));
        forwarder.setStatusListener(this::showStatus);
        input = new RemoteInputController(this, root, surface, forwarder);
        toolbar = new QuestToolbar(this, root, primary(), new QuestToolbar.Callbacks() {
            public void onScreenCountRequested(int count) { setMonitorCount(count); }
            public void onKeyboardRequested() { input.showKeyboard(); }
            public void onPointerToggle() { coordinator.togglePointer(BaseStreamActivity.this); }
            public void onSettingsRequested() {
                input.lostFocus(); Intent i = new Intent(BaseStreamActivity.this, MainActivity.class); i.putExtra("manage", true); i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); startActivity(i);
            }
            public void onDisconnectRequested() { coordinator.disconnect(); }
        });
        setContentView(root); root.requestFocus(); viewport.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyAspectRatio());
        showStatus("Connecting securely…"); policyChanged();
    }
    private void startStream() {
        if (!surfaceReady || destroyed || !coordinator.matches(generation) || stream != null) return;
        final long epoch = ++streamEpoch;
        stream = new StreamSession(this, surface, monitorIndex, new StreamSession.Listener() {
            private boolean current() { return !destroyed && epoch == streamEpoch && coordinator.matches(generation); }
            public void onStatus(String text) { runOnUiThread(() -> { if (current()) showStatus(text == null || text.isEmpty() ? streamingStatus() : text); }); }
            public void onStreamSize(int width, int height) { runOnUiThread(() -> { if (current()) { streamWidth = width; streamHeight = height; applyAspectRatio(); } }); }
            public void onAvailableMonitors(int count) { runOnUiThread(() -> { if (current() && primary()) { connection.availableMonitors = Math.max(1, Math.min(3, count)); updateMonitors(); } }); }
            public void onConnected() { runOnUiThread(() -> {
                if (!current()) return; forwarder.connected();
                if (primary()) { connection.connected = true; updateMonitors(); }
                showStatus(streamingStatus());
            }); }
            public void onDisconnected() { runOnUiThread(() -> {
                if (!current()) return; input.lostFocus(); forwarder.disconnected();
                if (primary()) connection.connected = false;
            }); }
            public void onInputCapability(boolean enabled) { runOnUiThread(() -> {
                if (current()) { inputAvailable = enabled; if (!enabled) input.lostFocus(); forwarder.inputCapability(enabled); if (connection.connected) showStatus(streamingStatus()); else if (!enabled) showStatus("View-only: remote input is disabled on the server"); }
            }); }
        });
        try {
            stream.configure(connection.endpoint.host, connection.endpoint.port, connection.pin, connection.token, primary() && connection.audio);
            forwarder.setSession(stream); stream.start();
        } catch (RuntimeException e) { stopStream(); showStatus("Cannot start this connection. Check the PC address and pairing details in Settings."); }
    }
    private void stopStream() {
        ++streamEpoch;
        if (input != null) input.lostFocus();
        forwarder.setSession(null);
        StreamSession previous = stream; stream = null;
        if (previous != null) previous.stop();
        if (primary() && connection != null) connection.connected = false;
    }
    private void setMonitorCount(int requested) {
        if (!primary() || !coordinator.matches(generation)) return;
        connection.desiredMonitors = Math.max(1, Math.min(connection.availableMonitors, requested));
        new AppSettings(this).preferences.edit().putInt("monitors", connection.desiredMonitors).apply();
        updateMonitors();
    }
    private void updateMonitors() {
        if (!primary() || !coordinator.matches(generation)) return;
        int count = Math.min(connection.desiredMonitors, connection.availableMonitors);
        for (BaseStreamActivity other : new ArrayList<>(ACTIVE)) if (other.generation == generation && other.monitorIndex >= count) other.finish();
        LAUNCHING.removeIf(i -> i >= count);
        if (connection.connected) for (int i = 1; i < count; i++) {
            boolean present = false; for (BaseStreamActivity other : ACTIVE) if (other.generation == generation && other.monitorIndex == i && !other.isFinishing()) present = true;
            if (present || LAUNCHING.contains(i)) continue;
            LAUNCHING.add(i); Intent intent = new Intent(this, MonitorActivity.class);
            intent.putExtra("generation", generation); intent.putExtra("monitor_index", i); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            try { startActivity(intent); } catch (RuntimeException e) { LAUNCHING.remove(i); showStatus("This Quest could not open another window. The main stream remains available."); }
        }
        toolbar.setScreenCount(count, connection.availableMonitors);
    }
    private String streamingStatus() { return inputAvailable ? "Streaming securely" : "Streaming securely • view-only (server input disabled)"; }
    private void showStatus(String text) {
        if (status == null || connection == null) return;
        String message = "metuxa  /  " + connection.endpoint.key() + "  /  monitor " + (monitorIndex + 1) + "\n" + text;
        status.setText(message);
        if (primary()) coordinator.lastStatus = text;
    }
    private void applyAspectRatio() {
        if (viewport == null || surface == null) return;
        int width = viewport.getWidth(), height = viewport.getHeight();
        if (width <= 0 || height <= 0 || streamWidth <= 0 || streamHeight <= 0) return;
        double scale = Math.min((double) width / streamWidth, (double) height / streamHeight);
        int w = Math.max(1, (int) Math.round(streamWidth * scale)), h = Math.max(1, (int) Math.round(streamHeight * scale));
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (old.width != w || old.height != h) surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; startStream(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { applyAspectRatio(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; stopStream(); }
    @Override public void policyChanged() { if (input != null) input.policyChanged(); if (toolbar != null) toolbar.setPointerMode(new AppSettings(this).pointerMode()); }
    @Override public void disconnectPanel() { stopStream(); finish(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (input != null) input.setWindowFocused(focused); }
    @Override protected void onPause() { if (input != null) input.setResumed(false); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (input != null) { input.setResumed(true); input.setWindowFocused(hasWindowFocus()); policyChanged(); } }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        View focused = getCurrentFocus();
        if (input != null && !(focused instanceof android.widget.Button) && !(focused instanceof android.widget.EditText) && input.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        if (primary()) new AlertDialog.Builder(this).setTitle("Disconnect all screens?").setMessage("Return to the launcher and release remote input.")
                .setPositiveButton("Disconnect", (d, w) -> coordinator.disconnect()).setNegativeButton("Keep streaming", null).show();
        else finish();
    }
    @Override protected void onDestroy() {
        if (!registered) { super.onDestroy(); return; }
        destroyed = true; stopStream(); if (input != null) input.release();
        ACTIVE.remove(this); LAUNCHING.remove(monitorIndex); coordinator.unregister(this);
        if (primary() && coordinator.matches(generation)) { LAUNCHING.clear(); coordinator.disconnect(); }
        else if (!primary() && coordinator.matches(generation)) {
            // A system-closed secondary remains closed until the user presses +.
            connection.desiredMonitors = Math.min(connection.desiredMonitors, Math.max(1, monitorIndex));
            for (BaseStreamActivity a : new ArrayList<>(ACTIVE)) if (a.primary() && a.generation == generation) a.updateMonitors();
        }
        super.onDestroy();
    }
}
