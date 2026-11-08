package com.metashare.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/** Settings shared by the launcher and every monitor window. Changes are persisted immediately. */
final class SettingsDialog {
    private final Activity activity;
    private final AppSettings settings;
    private final Runnable changed, manage, updates;
    SettingsDialog(Activity activity, Runnable changed, Runnable manage, Runnable updates) {
        this.activity = activity; settings = new AppSettings(activity); this.changed = changed; this.manage = manage; this.updates = updates;
    }
    AlertDialog show() {
        LinearLayout content = QuestUi.column(activity); int pad = QuestUi.dp(activity, 20); content.setPadding(pad, pad, pad, pad);
        QuestUi.heading(content, "Appearance");
        choice(content, "Theme", "theme", new String[] {"Dark", "Light", "Use headset theme"}, new int[] {0, 1, 2}, settings.theme());
        choice(content, "Text size", "text_scale", new String[] {"100%", "115%", "130%", "150%"}, new int[] {100, 115, 130, 150}, settings.textScale());
        note(content, "Theme and text size apply when you close settings. An open monitor briefly restarts its video.");
        QuestUi.divider(content); QuestUi.heading(content, "Monitor controls");
        String[] modes = {"Always visible", "Show on hover", "Hide after a delay"}; int[] values = {0, 1, 2};
        choice(content, "Top bar", "top_bar", modes, values, settings.topBar());
        choice(content, "Bottom bar", "bottom_bar", modes, values, settings.bottomBar());
        choice(content, "Hide controls after", "hide_delay", new String[] {"2 seconds", "4 seconds", "8 seconds", "15 seconds"}, new int[] {2, 4, 8, 15}, settings.hideDelay());
        choice(content, "Bar opacity", "panel_opacity", new String[] {"70%", "85%", "95%", "100%"}, new int[] {70, 85, 95, 100}, settings.panelOpacity());
        note(content, "Tap Controls to keep hidden bars open, then Hide controls to dismiss them. Bars set to Always visible stay open. You can also point at the top or bottom edge. Bars stay open while you use them. They cover the picture without shrinking it.");
        note(content, "Display changes the orientation of supported virtual desktops. Physical monitors mirror the PC exactly. Horizon controls the panel size; you can resize the panel in Home.");
        toggle(content, "Keep the display awake while connected", "keep_awake", settings.keepAwake());
        toggle(content, "Ask before disconnecting all screens", "confirm_disconnect", settings.confirmDisconnect());
        QuestUi.divider(content); QuestUi.heading(content, "Connection");
        toggle(content, "Find nearby PCs when the launcher opens", "discover_on_open", settings.discoverOnOpen());
        toggle(content, "Reconnect after a dropped connection", "reconnect", settings.reconnect());
        note(content, "Reconnection uses the same PC and saved certificate. A new PC always needs pairing.");
        android.widget.Button input = QuestUi.button(activity, "Input, sound and saved PCs", false); content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        QuestUi.divider(content); QuestUi.heading(content, "Updates");
        QuestSwitch checkUpdates = toggle(content, "Check for updates when the launcher opens", "auto_check_updates", settings.autoCheckUpdates());
        QuestSwitch downloads = toggle(content, "Download verified updates automatically", "auto_download_updates", settings.autoDownloadUpdates());
        downloads.setEnabled(settings.autoCheckUpdates());
        checkUpdates.setOnCheckedChangeListener((b, enabled) -> { settings.flag("auto_check_updates", enabled); downloads.setEnabled(enabled); });
        note(content, "Checks run at most once a day while the launcher is open. Android will still ask you to install. Updates never install during a connection.");
        android.widget.Button update = QuestUi.button(activity, "Check for updates", false); content.addView(update, new LinearLayout.LayoutParams(-1, -2));
        QuestUi.space(content, 12); note(content, activity.getString(R.string.app_version_label));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Settings").setView(scroll).setPositiveButton("Done", null).create();
        dialog.setOnDismissListener(d -> changed.run());
        input.setOnClickListener(v -> { dialog.dismiss(); manage.run(); }); update.setOnClickListener(v -> { dialog.dismiss(); updates.run(); });
        dialog.show(); return dialog;
    }
    private void note(LinearLayout parent, String text) { QuestUi.space(parent, 8); parent.addView(QuestUi.text(activity, text, 13, QuestUi.color(activity, R.color.quest_muted))); }
    private QuestSwitch toggle(LinearLayout parent, String title, String key, boolean value) {
        QuestSwitch check = new QuestSwitch(activity); check.setText(title); QuestUi.styleToggle(check); check.setChecked(value);
        check.setOnCheckedChangeListener((b, checked) -> settings.flag(key, checked)); parent.addView(check); return check;
    }
    private void choice(LinearLayout parent, String title, String key, String[] labels, int[] values, int current) {
        QuestUi.space(parent, 8); int selected = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == current) selected = i;
        QuestUi.choice(parent, title, labels, selected, position -> settings.integer(key, values[position]));
    }
}
