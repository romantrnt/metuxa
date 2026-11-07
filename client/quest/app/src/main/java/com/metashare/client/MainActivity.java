package com.metashare.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.InputDevice;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native launcher. Looking at the app is not consent to control an arbitrary PC. */
public final class MainActivity extends Activity {
    private final ConnectionCoordinator coordinator = ConnectionCoordinator.INSTANCE;
    private AppSettings settings;
    private EditText host, port, fingerprint, token;
    private CheckBox audio, autoConnect;
    private Spinner monitorCount;
    private RadioGroup pointerModes;
    private TextView status, discoveryStatus, sessionStatus;
    private LinearLayout hosts, columns, connectionCard, controlsCard;
    private Button scanButton, resumeButton, disconnectButton;
    private DiscoveryClient discovery;
    private boolean scanned, applyingProfile;
    private AlertDialog diagnosticsDialog;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); settings = new AppSettings(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        buildUi();
        for (AppSettings.Profile p : settings.profiles()) if (p.endpoint.key().equals(settings.lastHost())) { selectProfile(p); break; }
        if (state == null && !getIntent().getBooleanExtra("manage", false) && coordinator.current() == null && !settings.autoHost().isEmpty()) {
            for (AppSettings.Profile p : settings.profiles()) if (p.endpoint.key().equals(settings.autoHost())) {
                selectProfile(p); connect(); break;
            }
        }
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(QuestUi.BACKGROUND);
        LinearLayout page = QuestUi.column(this); int margin = QuestUi.dp(this, 24); page.setPadding(margin, margin, margin, margin);
        TextView brand = QuestUi.text(this, "metuxa", 38, QuestUi.TEXT); brand.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); page.addView(brand);
        page.addView(QuestUi.text(this, "Your PC, in your space.", 17, QuestUi.MUTED)); QuestUi.space(page, 16);
        sessionStatus = QuestUi.text(this, "", 14, QuestUi.ACCENT); sessionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); page.addView(sessionStatus);
        LinearLayout live = QuestUi.row(this); resumeButton = QuestUi.button(this, "Return to screens", true); disconnectButton = QuestUi.button(this, "Disconnect all", false);
        live.addView(resumeButton); live.addView(disconnectButton); page.addView(live);
        resumeButton.setOnClickListener(v -> openScreens()); disconnectButton.setOnClickListener(v -> { coordinator.disconnect(); refreshSession(); });
        QuestUi.space(page, 14);
        columns = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 850;
        columns.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout connection = QuestUi.column(this), controls = QuestUi.column(this);
        connectionCard = connection; controlsCard = controls;
        int pad = QuestUi.dp(this, 20); connection.setPadding(pad, pad, pad, pad); controls.setPadding(pad, pad, pad, pad);
        connection.setBackground(QuestUi.background(this, QuestUi.PANEL, 18)); controls.setBackground(QuestUi.background(this, QuestUi.PANEL, 18));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1.15f : 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1f : 0);
        if (wide) right.leftMargin = QuestUi.dp(this, 16); else right.topMargin = QuestUi.dp(this, 16);
        columns.addView(connection, left); columns.addView(controls, right); page.addView(columns);
        QuestUi.heading(connection, "Connect to your PC");
        connection.addView(QuestUi.text(this, "Start metuxa on the PC. Choose it here, then copy its fingerprint and pairing token.", 14, QuestUi.MUTED));
        QuestUi.space(connection, 10);
        LinearLayout hostActions = QuestUi.row(this);
        scanButton = QuestUi.button(this, "Find nearby PCs", false); hostActions.addView(scanButton); scanButton.setOnClickListener(v -> scan());
        Button saved = QuestUi.button(this, "Saved PCs", false); hostActions.addView(saved); saved.setOnClickListener(v -> showSaved()); connection.addView(hostActions);
        discoveryStatus = QuestUi.text(this, "Discovery only suggests addresses. It does not verify a PC's identity.", 12, QuestUi.MUTED); connection.addView(discoveryStatus);
        hosts = QuestUi.column(this); connection.addView(hosts);
        host = field(connection, "PC address", "192.168.1.20 or hostname", false); host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field(connection, "Base port", "7878", false); port.setInputType(InputType.TYPE_CLASS_NUMBER); port.setText("7878");
        fingerprint = field(connection, "Server SHA-256 fingerprint", "64 hex characters, from your PC", false);
        fingerprint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        token = field(connection, "Pairing token", "32 hex characters, from your PC", true);
        connection.addView(QuestUi.text(this, "Compare the fingerprint with the PC display. Tokens are stored encrypted on this headset. New server runs may require new pairing details.", 12, QuestUi.MUTED));
        QuestUi.space(connection, 10);
        autoConnect = check(connection, "Connect to this saved PC when metuxa opens", false);
        connection.addView(QuestUi.text(this, "Off by default. Reconnection during a session always stays on the same PC.", 12, QuestUi.MUTED));
        LinearLayout actions = QuestUi.row(this); Button connect = QuestUi.button(this, "Connect", true), save = QuestUi.button(this, "Save PC", false), forget = QuestUi.button(this, "Forget", false);
        actions.addView(connect); actions.addView(save); actions.addView(forget); connection.addView(actions);
        connect.setOnClickListener(v -> connect()); save.setOnClickListener(v -> { if (saveSettings()) showStatus("PC and settings saved.", false); }); forget.setOnClickListener(v -> forget());
        status = QuestUi.text(this, "Nothing connects until you choose Connect.", 13, QuestUi.MUTED); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); connection.addView(status);
        QuestUi.heading(controls, "Pointer control");
        controls.addView(QuestUi.text(this, "Applies immediately to every metuxa window, including new ones. The keyboard stays available.", 14, QuestUi.MUTED));
        pointerModes = new RadioGroup(this);
        for (int i = 0; i < 3; i++) {
            RadioButton button = new RadioButton(this); button.setId(100 + i); button.setMinHeight(QuestUi.dp(this, 48)); button.setTextColor(QuestUi.TEXT);
            button.setText(i == 0 ? "Full pointer input" : i == 1 ? "Block all remote pointer input" : "Selected physical devices (experimental)"); pointerModes.addView(button);
        }
        pointerModes.check(100 + settings.pointerMode()); controls.addView(pointerModes);
        pointerModes.setOnCheckedChangeListener((g, id) -> { if (!applyingProfile) coordinator.changePointerMode(this, id - 100); });
        Button devices = QuestUi.button(this, "Choose pointer devices", false); controls.addView(devices); devices.setOnClickListener(v -> chooseDevices());
        QuestUi.space(controls, 8);
        controls.addView(QuestUi.text(this, "Quest Home may report hands and controllers as the same Android input device. There is no reliable hand-only switch here. Block all pointer input is the dependable option; it does not disable hand tracking in Home.", 13, QuestUi.MUTED));
        QuestUi.space(controls, 22); QuestUi.heading(controls, "Screens & sound");
        audio = check(controls, "Play PC audio in the main window", settings.audio());
        controls.addView(QuestUi.text(this, "Only the main window plays audio. Server audio must also be enabled.", 12, QuestUi.MUTED));
        QuestUi.space(controls, 8); controls.addView(QuestUi.text(this, "Windows to open", 14, QuestUi.TEXT));
        monitorCount = new Spinner(this); monitorCount.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[] { "1 monitor", "2 monitors", "3 monitors" }));
        monitorCount.setSelection(settings.monitors() - 1); controls.addView(monitorCount, new LinearLayout.LayoutParams(-1, QuestUi.dp(this, 48)));
        controls.addView(QuestUi.text(this, "Sound and starting window count apply on the next connection. The toolbar can add or close available monitors.", 12, QuestUi.MUTED));
        Button saveOptions = QuestUi.button(this, "Save screen settings", false); controls.addView(saveOptions);
        saveOptions.setOnClickListener(v -> { settings.options(audio.isChecked(), monitorCount.getSelectedItemPosition() + 1, !settings.autoHost().isEmpty(), settings.autoHost()); showStatus("Screen settings saved for your next connection.", false); });
        QuestUi.space(controls, 22); QuestUi.heading(controls, "Input diagnostics");
        CheckBox diagnostics = check(controls, "Collect pointer metadata for this app session", coordinator.diagnostics.enabled());
        diagnostics.setOnCheckedChangeListener((b, checked) -> coordinator.diagnostics.setEnabled(checked));
        controls.addView(QuestUi.text(this, "Off by default. Source, tool type, device ID, action, buttons and device identity metadata only. No coordinates, keyboard events, typed text, screenshots or saved files. Up to 80 samples in memory.", 12, QuestUi.MUTED));
        Button inspect = QuestUi.button(this, "Inspect / reset metadata", false); controls.addView(inspect); inspect.setOnClickListener(v -> showDiagnostics());
        QuestUi.space(page, 18); page.addView(QuestUi.text(this, "metuxa • Quest client • 0.2.0\nIndependent install from MetaShare. No capture or remote control starts on the PC from this launcher alone.", 12, QuestUi.MUTED));
        scroll.addView(page); setContentView(scroll);
    }
    private EditText field(LinearLayout parent, String label, String hint, boolean secret) {
        QuestUi.space(parent, 10); TextView title = QuestUi.text(this, label, 14, QuestUi.TEXT); parent.addView(title);
        EditText edit = new EditText(this); edit.setId(View.generateViewId()); title.setLabelFor(edit.getId()); edit.setSingleLine(true); edit.setTextColor(QuestUi.TEXT); edit.setHintTextColor(QuestUi.MUTED); edit.setTextSize(15); edit.setHint(hint);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); edit.setSaveEnabled(!secret); edit.setMinHeight(QuestUi.dp(this, 48));
        parent.addView(edit, new LinearLayout.LayoutParams(-1, -2)); return edit;
    }
    private CheckBox check(LinearLayout parent, String label, boolean checked) {
        CheckBox c = new CheckBox(this); c.setText(label); c.setTextColor(QuestUi.TEXT); c.setMinHeight(QuestUi.dp(this, 48)); c.setChecked(checked); parent.addView(c); return c;
    }
    private void scan() {
        if (discovery != null) discovery.cancel();
        hosts.removeAllViews(); scanButton.setEnabled(false); discoveryStatus.setText("Looking for PCs on this LAN…");
        discovery = new DiscoveryClient(this); DiscoveryClient current = discovery;
        current.start(new DiscoveryClient.Listener() {
            public void found(HostEndpoint endpoint, int monitors, String name) {
                if (discovery != current || isFinishing()) return;
                Button b = QuestUi.button(MainActivity.this, (name.isEmpty() ? "metuxa PC" : name) + "\n" + endpoint.key() + " • " + monitors + " screen(s) • unverified", false);
                hosts.addView(b, new LinearLayout.LayoutParams(-1, -2)); b.setOnClickListener(v -> selectEndpoint(endpoint));
            }
            public void complete(String text) { if (discovery == current) { scanButton.setEnabled(true); discoveryStatus.setText(text); discovery = null; } }
        });
    }
    private void selectEndpoint(HostEndpoint endpoint) {
        AppSettings.Profile p = settings.find(endpoint);
        if (p != null) { selectProfile(p); return; }
        host.setText(endpoint.host); port.setText(Integer.toString(endpoint.port)); fingerprint.setText(""); token.setText(""); autoConnect.setChecked(false);
        showStatus("Address selected. Enter pairing details from this PC.", false);
    }
    private void selectProfile(AppSettings.Profile profile) {
        host.setText(profile.endpoint.host); port.setText(Integer.toString(profile.endpoint.port)); fingerprint.setText(profile.pin);
        try { token.setText(profile.token()); } catch (Exception e) { token.setText(""); showStatus("Saved token could not be opened. Enter the pairing token again.", true); }
        autoConnect.setChecked(settings.autoHost().equals(profile.endpoint.key()));
    }
    private void showSaved() {
        List<AppSettings.Profile> profiles = settings.profiles();
        if (profiles.isEmpty()) { showStatus("No saved PCs yet. Enter pairing details and choose Save PC or Connect.", false); return; }
        String[] labels = new String[profiles.size()]; for (int i = 0; i < labels.length; i++) labels[i] = profiles.get(i).endpoint.key();
        new AlertDialog.Builder(this).setTitle("Saved PCs").setItems(labels, (dialog, which) -> selectProfile(profiles.get(which))).setNegativeButton("Cancel", null).show();
    }
    private boolean saveSettings() {
        try {
            HostEndpoint endpoint = HostEndpoint.parse(host.getText().toString(), port.getText().toString());
            String pin = HostEndpoint.fingerprint(fingerprint.getText().toString()), secret = HostEndpoint.token(token.getText().toString());
            settings.save(endpoint, pin, secret); settings.options(audio.isChecked(), monitorCount.getSelectedItemPosition() + 1, autoConnect.isChecked(), endpoint.key()); return true;
        } catch (IllegalArgumentException e) { showStatus(e.getMessage(), true); }
        catch (Exception e) { showStatus("Could not save pairing details securely. Check device storage, then try again.", true); }
        return false;
    }
    private void connect() {
        if (!saveSettings()) return;
        HostEndpoint endpoint = HostEndpoint.parse(host.getText().toString(), port.getText().toString());
        Runnable connect = () -> {
            coordinator.connect(endpoint, HostEndpoint.fingerprint(fingerprint.getText().toString()), HostEndpoint.token(token.getText().toString()), audio.isChecked(), monitorCount.getSelectedItemPosition() + 1);
            openScreens(); refreshSession();
        };
        if (coordinator.current() != null) new AlertDialog.Builder(this).setTitle("Replace the current connection?")
                .setMessage("All current metuxa windows will disconnect before connecting to " + endpoint.key() + ".")
                .setPositiveButton("Reconnect", (d, w) -> connect.run()).setNegativeButton("Cancel", null).show();
        else connect.run();
    }
    private void openScreens() {
        ConnectionCoordinator.Connection c = coordinator.current(); if (c == null) return;
        Intent intent = new Intent(this, StreamActivity.class); intent.putExtra("generation", c.generation); intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); startActivity(intent);
    }
    private void forget() {
        final HostEndpoint endpoint;
        try { endpoint = HostEndpoint.parse(host.getText().toString(), port.getText().toString()); } catch (IllegalArgumentException e) { showStatus(e.getMessage(), true); return; }
        new AlertDialog.Builder(this).setTitle("Forget " + endpoint.key() + "?").setMessage("Removes its saved fingerprint, encrypted token and automatic connection preference. An open session is not disconnected.")
                .setPositiveButton("Forget", (d, w) -> { try { settings.forget(endpoint); fingerprint.setText(""); token.setText(""); autoConnect.setChecked(false); showStatus("PC forgotten.", false); }
                    catch (Exception e) { showStatus("Could not update saved PCs.", true); } }).setNegativeButton("Cancel", null).show();
    }
    private void chooseDevices() {
        List<InputDevice> devices = new ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) { InputDevice d = InputDevice.getDevice(id);
            if (d != null && !d.isVirtual() && (d.getSources() & InputDevice.SOURCE_CLASS_POINTER) != 0 && !d.getDescriptor().isEmpty()) devices.add(d); }
        if (devices.isEmpty()) { new AlertDialog.Builder(this).setTitle("No physical pointer devices found")
                .setMessage("Connect a mouse or other Android pointer device first. Selected-devices mode blocks all pointer input when none are selected. Quest may combine hands and controllers, so device selection cannot guarantee hand-only blocking.")
                .setPositiveButton("OK", null).show(); return; }
        Set<String> selected = settings.devices(); String[] labels = new String[devices.size()]; boolean[] checks = new boolean[devices.size()];
        for (int i = 0; i < devices.size(); i++) { InputDevice d = devices.get(i); labels[i] = d.getName() + " (Android device " + d.getId() + ")"; checks[i] = selected.contains(d.getDescriptor()); }
        new AlertDialog.Builder(this).setTitle("Allowed Android pointer devices")
                .setMultiChoiceItems(labels, checks, (dialog, which, checked) -> { String id = devices.get(which).getDescriptor(); if (checked) selected.add(id); else selected.remove(id); })
                .setPositiveButton("Save selection", (dialog, which) -> { settings.devices(selected); coordinator.notifyPolicy(); showStatus("Device selection saved. Choose selected-devices mode to apply it.", false); })
                .setNegativeButton("Cancel", null).show();
    }
    private void showDiagnostics() {
        TextView content = QuestUi.text(this, diagnosticsSnapshot(), 12, QuestUi.TEXT); content.setTypeface(android.graphics.Typeface.MONOSPACE); content.setPadding(24, 16, 24, 16);
        ScrollView scroller = new ScrollView(this); scroller.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Pointer metadata only").setView(scroller).setPositiveButton("Close", null).setNeutralButton("Reset", null).create();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable refresh = new Runnable() { public void run() { if (dialog.isShowing()) { content.setText(diagnosticsSnapshot()); handler.postDelayed(this, 1000); } } };
        diagnosticsDialog = dialog;
        dialog.setOnDismissListener(d -> { handler.removeCallbacks(refresh); if (diagnosticsDialog == dialog) diagnosticsDialog = null; }); dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> { coordinator.diagnostics.reset(); content.setText(diagnosticsSnapshot()); }); handler.post(refresh);
    }
    private String diagnosticsSnapshot() {
        StringBuilder text = new StringBuilder(coordinator.diagnostics.snapshot());
        if (!coordinator.diagnostics.enabled()) return text.append("\nCollection is off. Enable it to inspect device metadata.").toString();
        text.append("\nCurrent Android pointer devices (up to 16; IDs can change):\n");
        int count = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || (d.getSources() & InputDevice.SOURCE_CLASS_POINTER) == 0) continue;
            if (++count > 16) break;
            text.append("device=").append(id).append("  name=").append(metadataLabel(d.getName(), 80))
                .append("\nvendor=").append(d.getVendorId()).append("  product=").append(d.getProductId())
                .append("  virtual=").append(d.isVirtual()).append("  external=").append(d.isExternal())
                .append("  source=0x").append(Integer.toHexString(d.getSources()))
                .append("\ndescriptor=").append(metadataLabel(d.getDescriptor(), 128)).append("\n\n");
        }
        if (count == 0) text.append("No pointer devices currently listed.\n");
        text.append("Tool: 0 unknown, 1 finger, 2 stylus, 3 mouse, 4 eraser.\nAction: 0 down, 1 up, 2 move, 3 cancel, 7 hover, 8 scroll, 9 enter, 10 exit, 11 button-down, 12 button-up.\nAndroid metadata may combine hands and controllers. Device identity is not proof of input origin.");
        return text.toString();
    }
    private static String metadataLabel(String value, int maximum) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "");
        return clean.substring(0, Math.min(clean.length(), maximum));
    }
    private void showStatus(String text, boolean error) { if (status != null) { status.setText(text); status.setTextColor(error ? QuestUi.ERROR : QuestUi.MUTED); } }
    private void refreshSession() {
        ConnectionCoordinator.Connection c = coordinator.current();
        sessionStatus.setText(c == null ? coordinator.lastStatus : (c.connected ? "Connected to " : "Session for ") + c.endpoint.key() + "\n" + coordinator.lastStatus);
        resumeButton.setVisibility(c == null ? View.GONE : View.VISIBLE); disconnectButton.setVisibility(c == null ? View.GONE : View.VISIBLE);
        applyingProfile = true; pointerModes.check(100 + settings.pointerMode()); applyingProfile = false;
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        boolean wide = config.screenWidthDp >= 850;
        columns.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1.15f : 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1f : 0);
        if (wide) right.leftMargin = QuestUi.dp(this, 16); else right.topMargin = QuestUi.dp(this, 16);
        connectionCard.setLayoutParams(left); controlsCard.setLayoutParams(right);
    }
    @Override protected void onResume() { super.onResume(); refreshSession(); if (!scanned) { scanned = true; scan(); } }
    @Override protected void onStop() { if (diagnosticsDialog != null) diagnosticsDialog.dismiss(); if (discovery != null) { discovery.cancel(); discovery = null; scanButton.setEnabled(true); discoveryStatus.setText("Scan stopped. Choose Find nearby PCs to try again."); } super.onStop(); }
}
