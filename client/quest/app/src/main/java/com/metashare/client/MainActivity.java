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
import android.widget.CompoundButton;
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
    private QuestSwitch audio, autoConnect, moveInput, clickInput, scrollInput, keyboardInput;
    private TextView presetStatus;
    private final Button[] presetButtons = new Button[3];
    private Spinner monitorCount;
    private RadioGroup pointerModes;
    private final int[] pointerModeIds = new int[4];
    private TextView status, discoveryStatus, sessionStatus;
    private LinearLayout hosts, columns, connectionCard, controlsCard;
    private Button scanButton, resumeButton, disconnectButton;
    private DiscoveryClient discovery;
    private StreamMetadataProbe metadataProbe;
    private long pendingPrimaryGeneration = -1;
    private boolean scanned, applyingProfile, refreshingTheme;
    private AlertDialog diagnosticsDialog, pairingDialog, updateDialog, settingsDialog;
    private PairingClient pairing;
    private UpdateManager updater;
    private UpdateManager.Release availableUpdate;
    private TextView updateStatus;
    private Button installUpdate;
    private int appliedTheme, appliedScale, appliedUiMode;
    private static final class FormState {
        String host, port, fingerprint, token;
        boolean audio, autoConnect; int monitors; long pendingPrimaryGeneration;
    }
    @Override public Object onRetainNonConfigurationInstance() {
        FormState state = new FormState(); state.host = host.getText().toString(); state.port = port.getText().toString();
        state.fingerprint = fingerprint.getText().toString(); state.token = token.getText().toString();
        state.audio = audio.isChecked(); state.autoConnect = autoConnect.isChecked(); state.monitors = monitorCount.getSelectedItemPosition(); state.pendingPrimaryGeneration = pendingPrimaryGeneration; return state;
    }
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(QuestUi.themedContext(base)); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Runtime version text is independent of the stable manifest label.
        setTitle(R.string.app_version_label);
        settings = new AppSettings(this); updater = new UpdateManager(this);
        appliedTheme = settings.theme(); appliedScale = settings.textScale(); appliedUiMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        buildUi();
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof FormState) {
            FormState form = (FormState) retained; host.setText(form.host); port.setText(form.port); fingerprint.setText(form.fingerprint); token.setText(form.token);
            audio.setChecked(form.audio); autoConnect.setChecked(form.autoConnect); monitorCount.setSelection(form.monitors); pendingPrimaryGeneration = form.pendingPrimaryGeneration;
        } else for (AppSettings.Profile p : settings.profiles()) if (p.endpoint.key().equals(settings.lastHost())) { selectProfile(p); break; }
        if (state == null && retained == null && !getIntent().getBooleanExtra("manage", false) && coordinator.current() == null && !settings.autoHost().isEmpty()) {
            for (AppSettings.Profile p : settings.profiles()) if (p.endpoint.key().equals(settings.autoHost())) {
                selectProfile(p); connect(); break;
            }
        }
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(QuestUi.color(this, R.color.quest_background));
        LinearLayout page = QuestUi.column(this); int margin = QuestUi.dp(this, 20); page.setPadding(margin, margin, margin, margin);
        LinearLayout header = QuestUi.row(this), identity = QuestUi.column(this);
        TextView brand = QuestUi.text(this, "metuxa", 28, QuestUi.color(this, R.color.quest_text)); brand.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); brand.setLetterSpacing(-0.025f); brand.setAccessibilityHeading(true); identity.addView(brand);
        identity.addView(QuestUi.text(this, "PC monitors on your Quest", 14, QuestUi.color(this, R.color.quest_muted)));
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        Button settingsButton = QuestUi.button(this, "Settings", false); header.addView(settingsButton); settingsButton.setOnClickListener(v -> showSettings());
        page.addView(header); QuestUi.space(page, 20);
        sessionStatus = QuestUi.text(this, "", 14, QuestUi.color(this, R.color.quest_accent)); sessionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); page.addView(sessionStatus);
        LinearLayout live = QuestUi.actions(this); resumeButton = QuestUi.button(this, "Return to screens", true); disconnectButton = QuestUi.button(this, "Disconnect all", false);
        live.addView(resumeButton); live.addView(disconnectButton); page.addView(live);
        resumeButton.setOnClickListener(v -> openScreens()); disconnectButton.setOnClickListener(v -> { cancelMetadataProbe(true); coordinator.disconnect(); refreshSession(); });
        QuestUi.space(page, 14);
        columns = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 850;
        columns.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout connection = QuestUi.column(this), controls = QuestUi.column(this);
        connectionCard = connection; controlsCard = controls;
        int pad = QuestUi.dp(this, 20); connection.setPadding(pad, pad, pad, pad); controls.setPadding(pad, pad, pad, pad);
        connection.setBackground(QuestUi.outlined(this, QuestUi.color(this, R.color.quest_panel), 18, QuestUi.color(this, R.color.quest_border), 1)); controls.setBackground(QuestUi.outlined(this, QuestUi.color(this, R.color.quest_panel), 18, QuestUi.color(this, R.color.quest_border), 1));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1.15f : 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1f : 0);
        if (wide) right.leftMargin = QuestUi.dp(this, 16); else right.topMargin = QuestUi.dp(this, 16);
        columns.addView(connection, left); columns.addView(controls, right); page.addView(columns);
        QuestUi.heading(connection, "Connect to your PC");
        connection.addView(QuestUi.text(this, "On the PC, choose Pair a headset. Connect here within 60 seconds, compare the code, then confirm on both screens.", 14, QuestUi.color(this, R.color.quest_muted)));
        QuestUi.space(connection, 10);
        LinearLayout hostActions = QuestUi.actions(this);
        scanButton = QuestUi.button(this, "Find nearby PCs", false); hostActions.addView(scanButton); scanButton.setOnClickListener(v -> scan());
        Button saved = QuestUi.button(this, "Saved PCs", false); hostActions.addView(saved); saved.setOnClickListener(v -> showSaved()); connection.addView(hostActions); QuestUi.space(connection, 10);
        discoveryStatus = QuestUi.text(this, "Nearby PCs are unverified until you pair them.", 13, QuestUi.color(this, R.color.quest_muted)); connection.addView(discoveryStatus);
        hosts = QuestUi.column(this); connection.addView(hosts);
        host = field(connection, "PC address", "192.168.1.20 or hostname", false); host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field(connection, "Base port", "7878", false); port.setInputType(InputType.TYPE_CLASS_NUMBER); port.setText("7878");
        QuestUi.space(connection, 8);
        Button manual = QuestUi.button(this, "Manual pairing details", false); connection.addView(manual);
        LinearLayout manualFields = QuestUi.column(this); manualFields.setVisibility(View.GONE); connection.addView(manualFields);
        manual.setOnClickListener(v -> manualFields.setVisibility(manualFields.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        fingerprint = field(manualFields, "Server SHA-256 fingerprint", "64 hex characters, from your PC", false);
        fingerprint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        token = field(manualFields, "Pairing token", "32 hex characters, from your PC", true);
        manualFields.addView(QuestUi.text(this, "Compare the fingerprint with the PC display. Tokens are stored encrypted on this headset. New server runs may require new pairing details.", 13, QuestUi.color(this, R.color.quest_muted)));
        QuestUi.space(connection, 10);
        autoConnect = check(manualFields, "Connect to this saved PC when metuxa opens", false);
        manualFields.addView(QuestUi.text(this, "Off by default. Reconnection during a session always stays on the same PC.", 13, QuestUi.color(this, R.color.quest_muted)));
        QuestUi.space(connection, 16); LinearLayout actions = QuestUi.actions(this); Button connect = QuestUi.button(this, "Connect", true), save = QuestUi.button(this, "Save PC", false), forget = QuestUi.button(this, "Forget", false);
        actions.addView(connect); actions.addView(forget); manualFields.addView(save, new LinearLayout.LayoutParams(-1, -2)); connection.addView(actions); QuestUi.space(connection, 12);
        connect.setOnClickListener(v -> connect()); save.setOnClickListener(v -> { if (saveSettings()) showStatus("PC and settings saved.", false); }); forget.setOnClickListener(v -> forget());
        status = QuestUi.text(this, "Choose a PC to connect.", 13, QuestUi.color(this, R.color.quest_muted)); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); connection.addView(status);
        QuestUi.heading(controls, "Remote input");
        controls.addView(QuestUi.text(this, "Choose how you interact with your PC. Applies to every monitor.", 14, QuestUi.color(this, R.color.quest_muted)));
        QuestUi.space(controls, 12); presetStatus = QuestUi.text(this, "", 14, QuestUi.color(this, R.color.quest_muted)); controls.addView(presetStatus); QuestUi.space(controls, 10);
        LinearLayout presets = QuestUi.actions(this);
        String[] presetNames = { "Full remote", "Keyboard only", "View only" };
        for (int i = 0; i < presetNames.length; i++) {
            final int preset = i; Button b = QuestUi.button(this, presetNames[i], false); presetButtons[i] = b;
            presets.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
            b.setOnClickListener(v -> { coordinator.changeInputOptions(this, InputOptions.preset(preset)); refreshInputControls(); showStatus("Input preset applied to every window.", false); });
        }
        controls.addView(presets); QuestUi.space(controls, 8);
        InputOptions initialInput = settings.inputOptions();
        moveInput = check(controls, "Move / hover the PC pointer", initialInput.move);
        clickInput = check(controls, "Click, hold and drag buttons", initialInput.click);
        scrollInput = check(controls, "Scroll vertically and horizontally", initialInput.scroll);
        keyboardInput = check(controls, "Keyboard, IME and pasted text", initialInput.keyboard);
        moveInput.setOnCheckedChangeListener((b, checked) -> saveInputControls());
        clickInput.setOnCheckedChangeListener((b, checked) -> saveInputControls());
        scrollInput.setOnCheckedChangeListener((b, checked) -> saveInputControls());
        keyboardInput.setOnCheckedChangeListener((b, checked) -> saveInputControls());
        controls.addView(QuestUi.text(this, "With movement off, clicks use the PC's existing cursor: no hidden position update is sent. Dragging requires movement. Changing input settings releases held input and cancels pending pasted text.", 13, QuestUi.color(this, R.color.quest_muted)));
        QuestUi.space(controls, 20);
        LinearLayout inputCard = controls;
        controls = QuestUi.disclosure(inputCard, "Pointer device rules");
        controls.addView(QuestUi.text(this, "Device rules affect pointer actions only. The keyboard has its own switch above.", 13, QuestUi.color(this, R.color.quest_muted)));
        pointerModes = new RadioGroup(this);
        String[] sourceModes = { "All pointer devices (unfiltered)", "Block all pointer devices",
            "Only selected physical pointer devices (experimental)", "All identifiable physical pointer devices except selected" };
        for (int i = 0; i < sourceModes.length; i++) {
            RadioButton button = new RadioButton(this); pointerModeIds[i] = View.generateViewId(); button.setId(pointerModeIds[i]); button.setTag(i); QuestUi.styleToggle(button);
            button.setText(sourceModes[i]); pointerModes.addView(button);
        }
        pointerModes.check(pointerModeId(settings.pointerMode())); controls.addView(pointerModes);
        pointerModes.setOnCheckedChangeListener((g, id) -> { if (!applyingProfile) { coordinator.changePointerMode(this, selectedPointerMode()); refreshInputControls(); } });
        Button devices = QuestUi.button(this, "Choose allowed pointer devices", false); controls.addView(devices, new LinearLayout.LayoutParams(-1, -2)); QuestUi.space(controls, 8); devices.setOnClickListener(v -> chooseDevices(false));
        Button excluded = QuestUi.button(this, "Choose excluded pointer devices", false); controls.addView(excluded, new LinearLayout.LayoutParams(-1, -2)); excluded.setOnClickListener(v -> chooseDevices(true));
        QuestUi.space(controls, 8);
        controls.addView(QuestUi.text(this, "Both filtered modes block unknown, virtual or unidentified Android devices. An empty allowlist blocks every pointer; an empty exclusion list allows all identifiable non-virtual pointers. Unfiltered mode also permits unknown and virtual sources.", 13, QuestUi.color(this, R.color.quest_muted)));
        controls.addView(QuestUi.text(this, "Quest Home may report hands and controllers as the same Android device. Names and descriptors are not proof of physical origin. These rules cannot promise hand-only filtering. Block all pointer devices to prevent every pointer source from controlling the PC; Home hand tracking itself stays on.", 13, QuestUi.color(this, R.color.quest_muted)));
        refreshInputControls();
        controls = inputCard;
        QuestUi.divider(controls); QuestUi.heading(controls, "Screens & sound");
        audio = check(controls, "Play PC audio in the main window", settings.audio());
        controls.addView(QuestUi.text(this, "Only the main window plays audio. Server audio must also be enabled.", 13, QuestUi.color(this, R.color.quest_muted)));
        QuestUi.space(controls, 12); TextView windowsLabel = QuestUi.text(this, "Windows to open", 14, QuestUi.color(this, R.color.quest_text)); controls.addView(windowsLabel); QuestUi.space(controls, 6);
        monitorCount = new Spinner(this, Spinner.MODE_DROPDOWN); monitorCount.setId(View.generateViewId()); windowsLabel.setLabelFor(monitorCount.getId());
        ArrayAdapter<String> monitorAdapter = new ArrayAdapter<>(this, R.layout.quest_spinner_value, new String[] { "1 monitor", "2 monitors", "3 monitors" });
        monitorAdapter.setDropDownViewResource(R.layout.quest_spinner_option); monitorCount.setAdapter(monitorAdapter);
        monitorCount.setBackgroundTintList(null); monitorCount.setBackgroundResource(R.drawable.quest_spinner_background);
        monitorCount.setPopupBackgroundResource(R.drawable.quest_popup_background); monitorCount.setMinimumHeight(QuestUi.dp(this, 52));
        monitorCount.setSelection(settings.monitors() - 1); controls.addView(monitorCount, new LinearLayout.LayoutParams(-1, -2)); QuestUi.space(controls, 8);
        controls.addView(QuestUi.text(this, "Sound and starting window count apply on the next connection. The toolbar can add or close available monitors.", 13, QuestUi.color(this, R.color.quest_muted)));
        QuestUi.space(controls, 12); Button saveOptions = QuestUi.button(this, "Save screen settings", false); controls.addView(saveOptions, new LinearLayout.LayoutParams(-1, -2));
        saveOptions.setOnClickListener(v -> { settings.options(audio.isChecked(), monitorCount.getSelectedItemPosition() + 1, !settings.autoHost().isEmpty(), settings.autoHost()); showStatus("Screen settings saved for your next connection.", false); });
        QuestUi.space(controls, 20); controls = QuestUi.disclosure(inputCard, "Input diagnostics");
        QuestSwitch diagnostics = check(controls, "Collect pointer metadata for this app session", coordinator.diagnostics.enabled());
        diagnostics.setOnCheckedChangeListener((b, checked) -> coordinator.diagnostics.setEnabled(checked));
        controls.addView(QuestUi.text(this, "Keeps up to 80 pointer metadata samples in memory. It does not record positions, typing or screenshots.", 13, QuestUi.color(this, R.color.quest_muted)));
        QuestUi.space(controls, 12); Button inspect = QuestUi.button(this, "Inspect / reset metadata", false); controls.addView(inspect, new LinearLayout.LayoutParams(-1, -2)); inspect.setOnClickListener(v -> showDiagnostics());
        QuestUi.space(page, 18); page.addView(QuestUi.text(this, getString(R.string.app_version_label), 13, QuestUi.color(this, R.color.quest_muted)));
        scroll.addView(page); setContentView(scroll);
    }
    private EditText field(LinearLayout parent, String label, String hint, boolean secret) {
        QuestUi.space(parent, 16); TextView title = QuestUi.text(this, label, 14, QuestUi.color(this, R.color.quest_text)); parent.addView(title); QuestUi.space(parent, 6);
        EditText edit = new EditText(this); edit.setId(View.generateViewId()); title.setLabelFor(edit.getId()); edit.setSingleLine(true); edit.setTextColor(QuestUi.color(this, R.color.quest_text)); edit.setHintTextColor(QuestUi.color(this, R.color.quest_muted)); edit.setTextSize(15); edit.setHint(hint);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); edit.setSaveEnabled(!secret); edit.setMinHeight(QuestUi.dp(this, 52));
        edit.setBackgroundTintList(null); edit.setBackgroundResource(R.drawable.quest_field_background);
        edit.setPadding(QuestUi.dp(this, 14), QuestUi.dp(this, 12), QuestUi.dp(this, 14), QuestUi.dp(this, 12));
        parent.addView(edit, new LinearLayout.LayoutParams(-1, -2)); return edit;
    }
    private QuestSwitch check(LinearLayout parent, String label, boolean checked) {
        QuestSwitch c = new QuestSwitch(this); c.setText(label); QuestUi.styleToggle(c); c.setChecked(checked); parent.addView(c); return c;
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
        showStatus("PC selected. Choose Connect to pair.", false);
    }
    private void selectProfile(AppSettings.Profile profile) {
        host.setText(profile.endpoint.host); port.setText(Integer.toString(profile.endpoint.port)); fingerprint.setText(profile.pin);
        try { token.setText(profile.token()); } catch (Exception e) { token.setText(""); showStatus("Saved token could not be opened. Enter the pairing token again.", true); }
        autoConnect.setChecked(settings.autoHost().equals(profile.endpoint.key()));
    }
    private void showSaved() {
        List<AppSettings.Profile> profiles = settings.profiles();
        if (profiles.isEmpty()) { showStatus("No saved PCs yet. Open Manual pairing details to save a PC.", false); return; }
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
        final HostEndpoint endpoint;
        try { endpoint = HostEndpoint.parse(host.getText().toString(), port.getText().toString()); }
        catch (IllegalArgumentException error) { showStatus(error.getMessage(), true); return; }
        if (fingerprint.getText().toString().trim().isEmpty() && token.getText().toString().trim().isEmpty()) {
            if (coordinator.current() != null) new AlertDialog.Builder(this).setTitle("Replace the current connection?")
                .setMessage("All current screens will disconnect before pairing with " + endpoint.key() + ".")
                .setPositiveButton("Continue", (d, w) -> { coordinator.disconnect(); beginPairing(endpoint); }).setNegativeButton("Cancel", null).show();
            else beginPairing(endpoint);
            return;
        }
        if (!saveSettings()) return;
        Runnable connect = () -> {
            updater.cancel(); cancelMetadataProbe(true); coordinator.connect(endpoint, HostEndpoint.fingerprint(fingerprint.getText().toString()), HostEndpoint.token(token.getText().toString()), audio.isChecked(), monitorCount.getSelectedItemPosition() + 1);
            openScreens(); refreshSession();
        };
        if (coordinator.current() != null) new AlertDialog.Builder(this).setTitle("Replace the current connection?")
                .setMessage("All current metuxa windows will disconnect before connecting to " + endpoint.key() + ".")
                .setPositiveButton("Reconnect", (d, w) -> connect.run()).setNegativeButton("Cancel", null).show();
        else connect.run();
    }
    private void beginPairing(HostEndpoint endpoint) {
        updater.cancel(); cancelMetadataProbe(true); cancelPairing();
        final PairingClient[] attempt = new PairingClient[1];
        TextView code = QuestUi.text(this, "Connecting to " + endpoint.key() + "…", 20, QuestUi.color(this, R.color.quest_text));
        int pad = QuestUi.dp(this, 24); code.setPadding(pad, pad, pad, pad);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Pair with your PC").setView(code).setPositiveButton("Codes match", null).setNegativeButton("Cancel", null).create();
        pairingDialog = dialog;
        Runnable cancelThisAttempt = () -> { if (pairing == attempt[0] && pairingDialog == dialog) cancelPairing(); };
        dialog.setOnCancelListener(d -> cancelThisAttempt.run()); dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> cancelThisAttempt.run());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (pairing != attempt[0] || pairingDialog != dialog || pairing == null || isFinishing() || isDestroyed()) return;
            attempt[0].confirm(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            code.append("\n\nConfirmed here. Waiting for the PC…");
        });
        attempt[0] = new PairingClient(endpoint.host, endpoint.port, new PairingClient.Listener() {
            private boolean current() { return pairing == attempt[0] && pairingDialog == dialog && !isFinishing() && !isDestroyed(); }
            public void onCode(String value) { runOnUiThread(() -> { if (current()) { code.setText(value + "\n\nCompare every digit with the code on your PC. Confirm on both screens only if they match."); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); } }); }
            public void onPaired(String pin, String secret) { runOnUiThread(() -> {
                if (!current()) return; pairing = null; if (pairingDialog != null) { pairingDialog.dismiss(); pairingDialog = null; }
                settings.options(audio.isChecked(), monitorCount.getSelectedItemPosition() + 1, false, "");
                coordinator.connect(endpoint, pin, secret, settings.audio(), settings.monitors()); openScreens(); refreshSession();
            }); }
            public void onFailure(String message) { runOnUiThread(() -> {
                if (!current()) return; pairing = null; if (pairingDialog != null) { pairingDialog.dismiss(); pairingDialog = null; }
                showStatus(message, true);
            }); }
        });
        pairing = attempt[0]; pairing.start();
    }
    private void cancelPairing() {
        PairingClient old = pairing; pairing = null; if (old != null) old.cancel();
        if (pairingDialog != null) { pairingDialog.dismiss(); pairingDialog = null; }
    }
    private void showSettings() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        settingsDialog = new SettingsDialog(this, () -> {
            settingsDialog = null; BaseStreamActivity.refreshAllSettings();
            refreshTheme();
        }, this::showInputSettings, this::showUpdates).show();
    }
    private boolean themeChanged() { return appliedTheme != settings.theme() || appliedScale != settings.textScale(); }
    private boolean refreshTheme() {
        if (!themeChanged()) return false;
        if (!isDestroyed() && !isFinishing() && !refreshingTheme) { refreshingTheme = true; recreate(); }
        return true;
    }
    private void showInputSettings() {
        if (themeChanged()) { getIntent().putExtra("input_settings", true); refreshTheme(); return; }
        controlsCard.post(() -> { if (!isDestroyed()) ((ScrollView) columns.getParent().getParent()).smoothScrollTo(0, controlsCard.getTop() + columns.getTop()); });
    }
    private void showUpdates() {
        if (themeChanged()) { getIntent().putExtra("updates", true); refreshTheme(); return; }
        if (updateDialog != null && updateDialog.isShowing()) return;
        LinearLayout content = QuestUi.column(this); int pad = QuestUi.dp(this, 20); content.setPadding(pad, pad, pad, pad);
        updateStatus = QuestUi.text(this, getString(R.string.app_version_label) + "\n" + UpdateManager.lastInstallStatus(this), 15, QuestUi.color(this, R.color.quest_text)); content.addView(updateStatus);
        Button check = QuestUi.button(this, "Check now", false); content.addView(check); check.setOnClickListener(v -> checkUpdates(false));
        installUpdate = QuestUi.button(this, "Download and install", true); content.addView(installUpdate); installUpdate.setEnabled(availableUpdate != null);
        installUpdate.setOnClickListener(v -> installUpdate());
        updateDialog = new AlertDialog.Builder(this).setTitle("Updates").setView(content).setPositiveButton("Close", null).create();
        updateDialog.setOnDismissListener(d -> { updateDialog = null; updateStatus = null; installUpdate = null; }); updateDialog.show(); checkUpdates(false);
    }
    private UpdateManager.Listener updateListener() {
        return new UpdateManager.Listener() {
            public void onStatus(String text) { if (!isDestroyed()) { if (updateStatus != null) updateStatus.setText(text); else showStatus(text, false); } }
            public void onError(String text) { if (!isDestroyed()) { if (updateStatus != null) updateStatus.setText(text); else showStatus(text, true); } }
            public void onRelease(UpdateManager.Release release) {
                if (isDestroyed()) return; availableUpdate = release;
                if (installUpdate != null) installUpdate.setEnabled(true);
                onStatus("Version " + release.version + " is available.");
            }
        };
    }
    private void checkUpdates(boolean automatic) {
        if (automatic) {
            long now = System.currentTimeMillis(), last = settings.preferences.getLong("last_update_check", 0);
            if (!settings.autoCheckUpdates() || now - last >= 0 && now - last < 86400000L) return;
            settings.preferences.edit().putLong("last_update_check", now).apply();
        }
        UpdateManager.Listener ui = updateListener();
        updater.check(new UpdateManager.Listener() {
            public void onStatus(String text) { ui.onStatus(text); }
            public void onError(String text) { ui.onError(text); }
            public void onRelease(UpdateManager.Release release) {
                ui.onRelease(release);
                if (automatic && settings.autoDownloadUpdates() && coordinator.current() == null && !isDestroyed()) updater.download(release, ui);
            }
        });
    }
    private void installUpdate() {
        if (availableUpdate == null) return;
        if (coordinator.current() != null) { updateStatus.setText("Disconnect your screens before installing an update."); return; }
        if (updater.needsInstallPermission()) {
            new AlertDialog.Builder(this).setTitle("Allow metuxa updates?").setMessage("In Android settings, allow metuxa to install updates. Then return here and choose Download and install.")
                .setPositiveButton("Open Android settings", (d, w) -> updater.requestInstallPermission(this)).setNegativeButton("Cancel", null).show(); return;
        }
        new AlertDialog.Builder(this).setTitle("Install version " + availableUpdate.version + "?")
            .setMessage("The download is verified before Android asks you to install it.")
            .setPositiveButton("Download and install", (d, w) -> updater.downloadAndInstall(this, availableUpdate, updateListener())).setNegativeButton("Cancel", null).show();
    }
    private void openScreens() {
        updater.cancel();
        ConnectionCoordinator.Connection c = coordinator.current(); if (c == null) return;
        if (c.primaryLaunched || c.connected) { launchPrimary(c); return; } // Return to screens never probes a live peer.
        if (metadataProbe != null && pendingPrimaryGeneration == c.generation) return;
        cancelMetadataProbe(true); pendingPrimaryGeneration = c.generation;
        showStatus("Checking display size securely…", false);
        StreamMetadataProbe attempt = new StreamMetadataProbe(); metadataProbe = attempt;
        attempt.start(c.endpoint, c.pin, c.token, size -> finishMetadataProbe(attempt, c, size));
    }
    private void finishMetadataProbe(StreamMetadataProbe attempt, ConnectionCoordinator.Connection c, int[] size) {
        if (metadataProbe != attempt || !coordinator.matches(c.generation) || isFinishing() || isDestroyed()) return;
        metadataProbe = null;
        if (size != null) settings.streamSize(c.endpoint.key(), 0, size[0], size[1]);
        launchPrimary(c);
    }
    private void launchPrimary(ConnectionCoordinator.Connection c) {
        if (!coordinator.matches(c.generation)) return;
        pendingPrimaryGeneration = -1;
        Intent intent = new Intent(this, StreamActivity.class); intent.putExtra("generation", c.generation);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        int[] size = settings.streamSize(c.endpoint.key(), 0);
        try { startActivity(intent, PanelWindow.launchOptions(this, size[0], size[1])); c.primaryLaunched = true; }
        catch (RuntimeException failure) { showStatus("Could not open the monitor window. Choose Return to screens to try again.", true); }
    }
    private void cancelMetadataProbe(boolean forgetLaunch) {
        StreamMetadataProbe old = metadataProbe; metadataProbe = null; if (old != null) old.cancel();
        if (forgetLaunch) pendingPrimaryGeneration = -1;
    }
    private void forget() {
        final HostEndpoint endpoint;
        try { endpoint = HostEndpoint.parse(host.getText().toString(), port.getText().toString()); } catch (IllegalArgumentException e) { showStatus(e.getMessage(), true); return; }
        new AlertDialog.Builder(this).setTitle("Forget " + endpoint.key() + "?").setMessage("Removes its saved fingerprint, encrypted token and automatic connection preference. An open session is not disconnected.")
                .setPositiveButton("Forget", (d, w) -> { try { settings.forget(endpoint); fingerprint.setText(""); token.setText(""); autoConnect.setChecked(false); showStatus("PC forgotten.", false); }
                    catch (Exception e) { showStatus("Could not update saved PCs.", true); } }).setNegativeButton("Cancel", null).show();
    }
    private int pointerModeId(int mode) { return pointerModeIds[mode >= 0 && mode < pointerModeIds.length ? mode : PointerPolicy.BLOCK]; }
    private int selectedPointerMode() {
        View selected = pointerModes.findViewById(pointerModes.getCheckedRadioButtonId());
        return selected != null && selected.getTag() instanceof Integer ? (Integer) selected.getTag() : PointerPolicy.BLOCK;
    }
    private void saveInputControls() {
        if (applyingProfile || pointerModes == null) return;
        coordinator.changeInputOptions(this, new InputOptions(selectedPointerMode(),
            moveInput.isChecked(), clickInput.isChecked(), scrollInput.isChecked(), keyboardInput.isChecked()));
        refreshInputControls();
    }
    private void refreshInputControls() {
        if (pointerModes == null) return;
        InputOptions options = settings.inputOptions(); applyingProfile = true;
        moveInput.setChecked(options.move); clickInput.setChecked(options.click); scrollInput.setChecked(options.scroll); keyboardInput.setChecked(options.keyboard);
        pointerModes.check(pointerModeId(options.pointerMode)); presetStatus.setText("Active preset: " + options.presetLabel());
        for (Button button : presetButtons) if (button != null) button.setSelected(button.getText().toString().equals(options.presetLabel()));
        applyingProfile = false;
    }
    private void chooseDevices(boolean excluded) {
        Set<String> selected = excluded ? settings.excludedDevices() : settings.devices();
        java.util.LinkedHashMap<String, String> entries = new java.util.LinkedHashMap<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && !d.isVirtual() && (d.getSources() & InputDevice.SOURCE_CLASS_POINTER) != 0 && !d.getDescriptor().isEmpty()) {
                entries.put(d.getDescriptor(), metadataLabel(d.getName(), 80) + " (Android " + d.getId() + ", vendor " + d.getVendorId() + ", product " + d.getProductId() + ")");
            }
        }
        for (String descriptor : selected) if (!entries.containsKey(descriptor)) entries.put(descriptor, "Saved device, currently absent: " + metadataLabel(descriptor, 64));
        if (entries.isEmpty()) { new AlertDialog.Builder(this).setTitle("No identifiable physical pointers found")
            .setMessage("Connect a mouse or another Android pointer device first. Both filtered modes reject unknown and virtual sources. Android may combine hands and controllers, so device filtering cannot guarantee hand-only blocking.")
            .setPositiveButton("OK", null).show(); return; }
        List<String> descriptors = new ArrayList<>(entries.keySet()); String[] labels = new String[descriptors.size()]; boolean[] checks = new boolean[descriptors.size()];
        for (int i = 0; i < descriptors.size(); i++) { labels[i] = entries.get(descriptors.get(i)); checks[i] = selected.contains(descriptors.get(i)); }
        new AlertDialog.Builder(this).setTitle(excluded ? "Excluded Android pointer devices" : "Allowed Android pointer devices")
            .setMultiChoiceItems(labels, checks, (dialog, which, checked) -> { String id = descriptors.get(which); if (checked) selected.add(id); else selected.remove(id); })
            .setPositiveButton("Save selection", (dialog, which) -> {
                if (excluded) settings.excludedDevices(selected); else settings.devices(selected);
                coordinator.notifyPolicy(); refreshInputControls();
                showStatus(excluded ? "Exclusions saved. Choose the 'except selected' device rule to apply them." : "Allowlist saved. Choose the 'only selected' device rule to apply it.", false);
            }).setNeutralButton("Clear list", (dialog, which) -> {
                if (excluded) settings.excludedDevices(new HashSet<>()); else settings.devices(new HashSet<>());
                coordinator.notifyPolicy(); refreshInputControls(); showStatus(excluded ? "Exclusion list cleared." : "Allowlist cleared.", false);
            }).setNegativeButton("Cancel", null).show();
    }
    private void showDiagnostics() {
        TextView content = QuestUi.text(this, diagnosticsSnapshot(), 12, QuestUi.color(this, R.color.quest_text)); content.setTypeface(android.graphics.Typeface.MONOSPACE); content.setPadding(QuestUi.dp(this, 24), QuestUi.dp(this, 16), QuestUi.dp(this, 24), QuestUi.dp(this, 16));
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
    private void showStatus(String text, boolean error) { if (status != null) { status.setText(text); status.setTextColor(error ? QuestUi.color(this, R.color.quest_error) : QuestUi.color(this, R.color.quest_muted)); } }
    private void refreshSession() {
        ConnectionCoordinator.Connection c = coordinator.current();
        sessionStatus.setText(c == null ? coordinator.lastStatus : (c.connected ? "Connected to " : "Session for ") + c.endpoint.key() + "\n" + coordinator.lastStatus);
        resumeButton.setVisibility(c == null ? View.GONE : View.VISIBLE); disconnectButton.setVisibility(c == null ? View.GONE : View.VISIBLE);
        refreshInputControls();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (settings.theme() == 2 && (config.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) != appliedUiMode) { recreate(); return; }
        boolean wide = config.screenWidthDp >= 850;
        columns.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1.15f : 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1f : 0);
        if (wide) right.leftMargin = QuestUi.dp(this, 16); else right.topMargin = QuestUi.dp(this, 16);
        connectionCard.setLayoutParams(left); controlsCard.setLayoutParams(right);
    }
    @Override protected void onResume() { super.onResume(); if (refreshTheme()) return; refreshSession(); if (pendingPrimaryGeneration >= 0 && coordinator.matches(pendingPrimaryGeneration)) openScreens(); checkUpdates(true); if (getIntent().getBooleanExtra("updates", false)) { getIntent().removeExtra("updates"); showUpdates(); } if (getIntent().getBooleanExtra("input_settings", false)) { getIntent().removeExtra("input_settings"); showInputSettings(); } if (!scanned && settings.discoverOnOpen()) { scanned = true; scan(); } }
    @Override protected void onStop() { cancelMetadataProbe(false); updater.cancel(); cancelPairing(); if (diagnosticsDialog != null) diagnosticsDialog.dismiss(); if (discovery != null) { discovery.cancel(); discovery = null; scanButton.setEnabled(true); discoveryStatus.setText("Scan stopped. Choose Find nearby PCs to try again."); } super.onStop(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); }
    @Override protected void onDestroy() { cancelMetadataProbe(false); cancelPairing(); if (updater != null) updater.close(); if (updateDialog != null) updateDialog.dismiss(); if (settingsDialog != null) { settingsDialog.setOnDismissListener(null); settingsDialog.dismiss(); } super.onDestroy(); }
}
