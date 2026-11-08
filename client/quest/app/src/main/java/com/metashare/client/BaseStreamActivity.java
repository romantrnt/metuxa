package com.metashare.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Each panel has a renderer, but all panels belong to one explicitly selected session. */
abstract class BaseStreamActivity extends Activity implements RemoteSurfaceView.Listener, ConnectionCoordinator.Panel {
    private static final ArrayList<BaseStreamActivity> ACTIVE = new ArrayList<>();
    private static final Set<Integer> LAUNCHING = new HashSet<>();
    private final ConnectionCoordinator coordinator = ConnectionCoordinator.INSTANCE;
    private final SessionInputForwarder forwarder = new SessionInputForwarder();
    private ConnectionCoordinator.Connection connection;
    private RemoteSurfaceView surface;
    private FrameLayout viewport;
    private PanelOverlay root;
    private AppSettings settings;
    private int appliedTheme, appliedScale, appliedUiMode;
    private TextView status;
    private QuestToolbar toolbar;
    private AlertDialog settingsDialog;
    private RemoteInputController input;
    private StreamSession stream;
    private long generation, streamEpoch;
    private int monitorIndex, streamWidth, streamHeight;
    private boolean surfaceReady, destroyed, registered, inputAvailable, refreshingTheme, orientationSupported, orientationPending;
    private String displaySource = "unknown", displayFailure;
    abstract boolean primary();
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(QuestUi.themedContext(base)); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Runtime version text is independent of the stable manifest label.
        setTitle(R.string.app_version_label);
        settings = new AppSettings(this); appliedTheme = settings.theme(); appliedScale = settings.textScale(); appliedUiMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        generation = getIntent().getLongExtra("generation", -1);
        monitorIndex = primary() ? 0 : getIntent().getIntExtra("monitor_index", -1);
        connection = coordinator.current();
        if (!coordinator.matches(generation) || monitorIndex < 0 || monitorIndex > 2 || !primary() && (monitorIndex == 0 || monitorIndex >= Math.min(connection.desiredMonitors, connection.availableMonitors))) { finish(); return; }
        for (BaseStreamActivity other : ACTIVE) if (other.generation == generation && other.monitorIndex == monitorIndex && !other.isFinishing()) { finish(); return; }
        registered = true; ACTIVE.add(this); LAUNCHING.remove(monitorIndex); coordinator.register(this);
        int[] remembered = settings.streamSize(connection.endpoint.key(), monitorIndex);
        PanelWindow.followStream(this, remembered[0], remembered[1]);
        applyKeepAwake();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root = new PanelOverlay(this, () -> { if (input != null) input.lostFocus(); }); root.setBackgroundColor(QuestUi.color(this, R.color.quest_background)); root.setFocusableInTouchMode(true);
        viewport = new FrameLayout(this); viewport.setBackgroundColor(android.graphics.Color.BLACK);
        FrameLayout.LayoutParams viewportParams = new FrameLayout.LayoutParams(-1, -1);
        root.addView(viewport, viewportParams);
        surface = new RemoteSurfaceView(this); surface.setRenderListener(this); surface.setFocusableInTouchMode(true); surface.setContentDescription("Remote monitor " + (monitorIndex + 1) + " on " + connection.endpoint.key());
        viewport.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        status = QuestUi.text(this, "", 13, QuestUi.color(this, R.color.quest_muted)); status.setMaxLines(3); status.setBackgroundColor(QuestUi.color(this, R.color.quest_panel)); status.setGravity(Gravity.CENTER_VERTICAL); status.setPadding(QuestUi.dp(this, 16), QuestUi.dp(this, 6), QuestUi.dp(this, 152), QuestUi.dp(this, 6)); status.setClickable(true); status.setMinHeight(QuestUi.dp(this, 64)); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        forwarder.setStatusListener(this::showStatus);
        input = new RemoteInputController(this, root, surface, forwarder);
        toolbar = new QuestToolbar(this, root, primary(), new QuestToolbar.Callbacks() {
            public void onLocalControlActivated() { input.lostFocus(); }
            public void onScreenCountRequested(int count) { setMonitorCount(count); }
            public void onKeyboardRequested() { input.showKeyboard(); }
            public void onPointerToggle() { coordinator.togglePointer(BaseStreamActivity.this); }
            public void onSettingsRequested() {
                showSettings();
            }
            public void onDisconnectRequested() { requestDisconnect(); }
            public void onOrientationRequested() { chooseOrientation(); }
        });
        toolbar.setOrientation(0, 0, false); root.panels(status, toolbar.view());
        setContentView(root); root.requestFocus(); viewport.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyAspectRatio());
        showStatus("Connecting securely…"); policyChanged();
    }
    private void startStream() {
        if (!surfaceReady || destroyed || !coordinator.matches(generation) || stream != null) return;
        final long epoch = ++streamEpoch;
        stream = new StreamSession(this, surface, monitorIndex, new StreamSession.Listener() {
            private boolean current() { return !destroyed && epoch == streamEpoch && coordinator.matches(generation); }
            public void onStatus(String text) { runOnUiThread(() -> { if (current()) showStatus(text == null || text.isEmpty() ? streamingStatus() : text); }); }
            public void onStreamSize(int width, int height) { runOnUiThread(() -> { if (current()) { updateStreamSize(width, height); } }); }
            public void onDisplayCapability(String source, boolean supported) { runOnUiThread(() -> {
                if (!current()) return; displaySource = source; orientationSupported = supported;
                toolbar.setOrientation(streamWidth, streamHeight, orientationPending);
            }); }
            public void onDisplayResult(String result, int width, int height) { runOnUiThread(() -> {
                if (!current()) return;
                displayResult(result, width, height);
            }); }
            public void onAvailableMonitors(int count) { runOnUiThread(() -> { if (current() && primary()) { connection.availableMonitors = Math.max(1, Math.min(3, count)); updateMonitors(); } }); }
            public void onConnected() { runOnUiThread(() -> {
                if (!current()) return; displayFailure = null; forwarder.connected();
                if (primary()) { connection.connected = true; updateMonitors(); }
                showStatus(streamingStatus());
            }); }
            public void onDisconnected() { runOnUiThread(() -> {
                if (!current()) return; input.lostFocus(); forwarder.disconnected();
                orientationPending = false; orientationSupported = false; toolbar.setOrientation(streamWidth, streamHeight, false);
                if (primary()) connection.connected = false;
            }); }
            public void onInputCapability(boolean enabled) { runOnUiThread(() -> {
                if (current()) { inputAvailable = enabled; if (!enabled) input.lostFocus(); forwarder.inputCapability(enabled); if (enabled) input.policyChanged(); if (connection.connected) showStatus(streamingStatus()); else if (!enabled) showStatus("View-only: remote input is disabled on the server"); }
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
            int[] size = settings.streamSize(connection.endpoint.key(), i);
            try { startActivity(intent, PanelWindow.launchOptions(this, size[0], size[1])); } catch (RuntimeException e) { LAUNCHING.remove(i); showStatus("This Quest could not open another window. The main stream remains available."); }
        }
        toolbar.setScreenCount(count, connection.availableMonitors);
    }
    private String streamingStatus() { return inputAvailable ? "Connected" : "Connected · remote input is off on the PC"; }
    private void showStatus(String text) {
        if (status == null || connection == null) return;
        if (displayFailure != null) text = displayFailure;
        String message = "metuxa  /  " + connection.endpoint.key() + "  /  monitor " + (monitorIndex + 1) + "\n" + text;
        status.setText(message);
        if (primary()) coordinator.lastStatus = text;
    }
    private void applyAspectRatio() {
        if (viewport == null || surface == null) return;
        int width = viewport.getWidth(), height = viewport.getHeight();
        if (width <= 0 || height <= 0 || streamWidth <= 0 || streamHeight <= 0) return;
        int[] size = MonitorGeometry.fit(width, height, streamWidth, streamHeight, 0);
        int w = size[0], h = size[1];
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (old.width != w || old.height != h) surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }
    private void applyKeepAwake() {
        if (settings.keepAwake()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void displayResult(String result, int width, int height) {
        orientationPending = false; toolbar.setOrientation(streamWidth, streamHeight, false);
        if ("restart_required".equals(result)) {
            displayFailure = "This virtual display could not be restored. Stop and start sharing on the PC, then pair again.";
            orientationSupported = false; showStatus(displayFailure);
        } else if ("ok".equals(result)) { updateStreamSize(width, height); showStatus("Desktop orientation changed. Reconnecting…"); }
        else showStatus("busy".equals(result) ? "The PC is already changing this display. Try again shortly."
            : "unsupported".equals(result) ? "This source follows the PC. Use a virtual monitor for independent portrait."
            : "timeout".equals(result) ? "The display change timed out. Reconnecting to check its current orientation…"
            : "The PC could not change orientation. Reconnecting to check its current display size…");
    }
    private void updateStreamSize(int width, int height) {
        if (width < 16 || width > 8192 || height < 16 || height > 8192) return;
        streamWidth = width; streamHeight = height;
        settings.streamSize(connection.endpoint.key(), monitorIndex, width, height);
        PanelWindow.followStream(this, width, height);
        applyAspectRatio(); toolbar.setOrientation(width, height, orientationPending);
    }
    private void chooseOrientation() {
        input.lostFocus();
        if (!orientationSupported) {
            String explanation = "physical".equals(displaySource)
                ? "This panel mirrors the physical monitor exactly. Its orientation follows the PC. Add a virtual monitor for an independent portrait desktop."
                : "window".equals(displaySource) ? "This panel follows the shared PC window. Resize that window on the PC, or add a virtual monitor for an independent portrait desktop."
                : "The PC has not offered independent orientation for this source. Only metuxa virtual monitors can change orientation here. Physical monitors always follow the PC.";
            new AlertDialog.Builder(this).setTitle("Desktop orientation").setMessage(explanation)
                .setPositiveButton("Done", null).show(); return;
        }
        if (orientationPending) { showStatus("Waiting for the PC to change this virtual monitor…"); return; }
        new AlertDialog.Builder(this).setTitle("Virtual desktop orientation")
            .setSingleChoiceItems(new String[] {"Landscape", "Portrait"}, streamHeight > streamWidth ? 1 : 0, (dialog, selected) -> {
                dialog.dismiss();
                if ((selected == 1) == (streamHeight > streamWidth)) return;
                input.lostFocus();
                if (stream != null && stream.requestOrientation(selected == 1)) {
                    orientationPending = true; toolbar.setOrientation(streamWidth, streamHeight, true);
                    showStatus("Changing virtual desktop orientation on the PC…");
                } else showStatus("Desktop orientation is not available right now. Wait for the connection and try again.");
            }).setNegativeButton("Cancel", null).show();
    }
    private void showSettings() {
        input.lostFocus();
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        settingsDialog = new SettingsDialog(this, () -> {
            settingsDialog = null;
            refreshAllSettings();
        }, () -> {
            openLauncher(false);
        }, () -> openLauncher(true)).show();
    }
    static void refreshAllSettings() {
        // Quest can keep several panels resumed; waiting for onResume leaves peers stale.
        for (BaseStreamActivity panel : new ArrayList<>(ACTIVE)) panel.refreshSettings();
    }
    private boolean refreshSettings() {
        if (destroyed || isFinishing() || settings == null) return false;
        if (appliedTheme != settings.theme() || appliedScale != settings.textScale()) {
            if (!refreshingTheme) { refreshingTheme = true; recreate(); }
            return true;
        }
        applyKeepAwake(); if (root != null) root.configure(); return false;
    }
    private void openLauncher(boolean updates) {
        Intent i = new Intent(this, MainActivity.class); i.putExtra("manage", true); i.putExtra("updates", updates);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); startActivity(i);
    }
    private void requestDisconnect() {
        input.lostFocus();
        if (!settings.confirmDisconnect()) { coordinator.disconnect(); return; }
        new AlertDialog.Builder(this).setTitle("Disconnect all screens?")
            .setPositiveButton("Disconnect", (d, w) -> coordinator.disconnect()).setNegativeButton("Keep streaming", null).show();
    }
    @Override public void renderSurfaceAvailable() { surfaceReady = true; startStream(); }
    @Override public void renderSurfaceSizeChanged() { applyAspectRatio(); }
    @Override public void renderSurfaceDestroyed() { surfaceReady = false; stopStream(); }
    @Override public void policyChanged() { if (input != null) input.policyChanged(); if (toolbar != null) toolbar.setInputOptions(new AppSettings(this).inputOptions()); }
    @Override public void disconnectPanel() { stopStream(); finish(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (input != null) input.setWindowFocused(focused); }
    @Override protected void onPause() { if (input != null) input.setResumed(false); if (root != null) root.resumed(false); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (refreshSettings()) return; if (root != null) { root.resumed(true); root.configure(); applyKeepAwake(); } if (input != null) { input.setResumed(true); input.setWindowFocused(hasWindowFocus()); policyChanged(); } }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // A remotely pressed key must be released even if focus moved to a
        // native button meanwhile. Still deliver the event to that local control.
        boolean releasedRemoteKey = input != null && input.releaseHeldKey(event);
        View focused = getCurrentFocus();
        if (focused instanceof android.widget.Button || focused instanceof android.widget.EditText) return super.dispatchKeyEvent(event);
        if (releasedRemoteKey || input != null && input.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (settings != null && settings.theme() == 2 && (config.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) != appliedUiMode) recreate();
        else applyAspectRatio();
    }
    @Override public void onBackPressed() { if (primary()) requestDisconnect(); else finish(); }
    @Override protected void onDestroy() {
        if (!registered) { super.onDestroy(); return; }
        destroyed = true; if (settingsDialog != null) settingsDialog.dismiss(); if (root != null) root.close(); stopStream(); if (input != null) input.release();
        ACTIVE.remove(this); LAUNCHING.remove(monitorIndex); coordinator.unregister(this);
        if (!isChangingConfigurations() && primary() && coordinator.matches(generation)) { LAUNCHING.clear(); coordinator.disconnect(); }
        else if (!isChangingConfigurations() && !primary() && coordinator.matches(generation)) {
            // A system-closed secondary remains closed until the user presses +.
            connection.desiredMonitors = Math.min(connection.desiredMonitors, Math.max(1, monitorIndex));
            for (BaseStreamActivity a : new ArrayList<>(ACTIVE)) if (a.primary() && a.generation == generation) a.updateMonitors();
        }
        super.onDestroy();
    }
}
