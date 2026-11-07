package com.metashare.client;

import android.app.Activity;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Always reachable controls, including when every remote pointer source is blocked. */
final class QuestToolbar {
    interface Callbacks {
        void onScreenCountRequested(int count);
        void onKeyboardRequested();
        void onPointerToggle();
        void onSettingsRequested();
        void onDisconnectRequested();
    }
    private final Button pointer, minus, plus, keyboard;
    private InputOptions options = InputOptions.full();
    private final TextView count;
    private int screenCount = 1;
    QuestToolbar(Activity activity, FrameLayout root, boolean primary, Callbacks callbacks) {
        HorizontalScrollView scroll = new HorizontalScrollView(activity); scroll.setHorizontalScrollBarEnabled(false); scroll.setFillViewport(true);
        scroll.setBackground(QuestUi.background(activity, QuestUi.PANEL, 0));
        LinearLayout controls = QuestUi.row(activity); controls.setPadding(QuestUi.dp(activity, 10), 0, QuestUi.dp(activity, 10), 0);
        keyboard = QuestUi.button(activity, "Keyboard", false); controls.addView(keyboard); keyboard.setOnClickListener(v -> callbacks.onKeyboardRequested());
        pointer = QuestUi.button(activity, "Pointer: on", false); controls.addView(pointer); pointer.setOnClickListener(v -> { if (options.pointerActions()) callbacks.onPointerToggle(); else callbacks.onSettingsRequested(); });
        minus = QuestUi.button(activity, "−", false); minus.setContentDescription("Close the last monitor window"); minus.setOnClickListener(v -> callbacks.onScreenCountRequested(screenCount - 1));
        plus = QuestUi.button(activity, "+", false); plus.setContentDescription("Open another monitor window"); plus.setOnClickListener(v -> callbacks.onScreenCountRequested(screenCount + 1));
        count = QuestUi.text(activity, "1 / 1", 14, QuestUi.MUTED); count.setPadding(12, 0, 12, 0);
        if (primary) { controls.addView(minus); controls.addView(count); controls.addView(plus); }
        Button settings = QuestUi.button(activity, "Settings", false); controls.addView(settings); settings.setOnClickListener(v -> callbacks.onSettingsRequested());
        Button disconnect = QuestUi.button(activity, "Disconnect", false); disconnect.setTextColor(QuestUi.ERROR);
        disconnect.setContentDescription("Disconnect all metuxa screens"); disconnect.setOnClickListener(v -> callbacks.onDisconnectRequested());
        scroll.addView(controls, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, QuestUi.dp(activity, 68), Gravity.BOTTOM);
        // Disconnect is fixed in place, never hidden by overflow on a narrow panel.
        FrameLayout footer = new FrameLayout(activity); footer.setBackgroundColor(QuestUi.PANEL);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(-1, -1); scrollParams.rightMargin = QuestUi.dp(activity, 138);
        footer.addView(scroll, scrollParams);
        FrameLayout.LayoutParams disconnectParams = new FrameLayout.LayoutParams(QuestUi.dp(activity, 132), QuestUi.dp(activity, 52), Gravity.END | Gravity.CENTER_VERTICAL);
        disconnectParams.rightMargin = QuestUi.dp(activity, 4); footer.addView(disconnect, disconnectParams);
        root.addView(footer, params);
        setScreenCount(1, 1); setInputOptions(new AppSettings(activity).inputOptions());
    }
    void setScreenCount(int current, int maximum) {
        screenCount = Math.max(1, current); count.setText(screenCount + " / " + maximum);
        count.setContentDescription(screenCount + " of " + maximum + " monitor windows");
        minus.setEnabled(screenCount > 1); plus.setEnabled(screenCount < maximum);
    }
    void setInputOptions(InputOptions next) {
        options = next;
        int mode = options.pointerMode;
        String label = !options.pointerActions() ? "Pointer settings" : mode == PointerPolicy.BLOCK ? "Pointer: blocked"
            : mode == PointerPolicy.DEVICES ? "Pointer: allowed" : mode == PointerPolicy.EXCEPT_DEVICES ? "Pointer: except"
            : options.move && options.click && options.scroll ? "Pointer: on" : "Pointer: custom";
        pointer.setText(label);
        pointer.setContentDescription(PointerPolicy.label(mode) + ". Move " + options.move + ", click " + options.click + ", scroll " + options.scroll + ". Applies to all windows.");
        keyboard.setEnabled(options.keyboard); keyboard.setText(options.keyboard ? "Keyboard" : "Keyboard off");
        keyboard.setContentDescription(options.keyboard ? "Open remote keyboard" : "Remote keyboard disabled in settings");
    }
    static void styleStatus(TextView text) { text.setTextSize(13); text.setTextColor(QuestUi.MUTED); text.setPadding(16, 10, 16, 10); }
}
