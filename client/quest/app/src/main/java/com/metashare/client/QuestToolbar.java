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
        default void onAboutRequested() {}
        default void onOrientationRequested() {}
        default void onLocalControlActivated() {}
    }
    private final Button pointer, minus, plus, keyboard, orientation;
    private final FrameLayout footer;
    private InputOptions options = InputOptions.full();
    private final TextView count;
    private int screenCount = 1;
    QuestToolbar(Activity activity, FrameLayout root, boolean primary, Callbacks callbacks) {
        HorizontalScrollView scroll = new HorizontalScrollView(activity); scroll.setHorizontalScrollBarEnabled(false); scroll.setFillViewport(true);
        scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        LinearLayout controls = QuestUi.row(activity); controls.setPadding(QuestUi.dp(activity, 10), 0, QuestUi.dp(activity, 10), 0);
        keyboard = QuestUi.button(activity, "Keyboard", false); controls.addView(keyboard); keyboard.setOnClickListener(v -> { callbacks.onLocalControlActivated(); callbacks.onKeyboardRequested(); });
        pointer = QuestUi.button(activity, "Pointer: on", false); controls.addView(pointer); pointer.setOnClickListener(v -> { callbacks.onLocalControlActivated(); if (options.pointerActions()) callbacks.onPointerToggle(); else callbacks.onSettingsRequested(); });
        minus = QuestUi.button(activity, "−", false); minus.setContentDescription("Close the last monitor window"); minus.setOnClickListener(v -> { callbacks.onLocalControlActivated(); callbacks.onScreenCountRequested(screenCount - 1); });
        plus = QuestUi.button(activity, "+", false); plus.setContentDescription("Open another monitor window"); plus.setOnClickListener(v -> { callbacks.onLocalControlActivated(); callbacks.onScreenCountRequested(screenCount + 1); });
        count = QuestUi.text(activity, "1 / 1", 14, QuestUi.color(activity, R.color.quest_muted)); count.setGravity(Gravity.CENTER); count.setPadding(QuestUi.dp(activity, 8), 0, QuestUi.dp(activity, 8), 0);
        if (primary) { controls.addView(minus); controls.addView(count); controls.addView(plus); }
        orientation = QuestUi.button(activity, "Display", false); controls.addView(orientation); orientation.setOnClickListener(v -> { callbacks.onLocalControlActivated(); callbacks.onOrientationRequested(); });
        Button disconnect = QuestUi.button(activity, "Disconnect", false); disconnect.setTextColor(QuestUi.color(activity, R.color.quest_error));
        disconnect.setContentDescription("Disconnect all metuxa screens"); disconnect.setOnClickListener(v -> { callbacks.onLocalControlActivated(); callbacks.onDisconnectRequested(); });
        for (int i = 0; i < controls.getChildCount(); i++) {
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) item.setMarginStart(QuestUi.dp(activity, 6)); controls.getChildAt(i).setLayoutParams(item);
        }
        scroll.addView(controls, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height(activity), Gravity.BOTTOM);
        // More exposes every action without requiring a horizontal swipe on Quest.
        Button more = QuestUi.overflowButton(activity, "More options");
        more.setOnClickListener(v -> {
            callbacks.onLocalControlActivated();
            android.widget.PopupMenu menu = new android.widget.PopupMenu(activity, more);
            menu.getMenu().add(0, 1, 0, "Settings"); menu.getMenu().add(0, 2, 1, "Desktop orientation");
            menu.getMenu().add(0, 3, 2, "Open keyboard").setEnabled(options.keyboard);
            menu.getMenu().add(0, 4, 3, "Pointer controls");
            if (primary) {
                menu.getMenu().add(0, 5, 4, "Open another monitor").setEnabled(plus.isEnabled());
                menu.getMenu().add(0, 6, 5, "Close the last monitor").setEnabled(minus.isEnabled());
            }
            menu.getMenu().add(0, 7, 6, "Disconnect all screens");
            menu.getMenu().add(0, 8, 7, "About");
            menu.setOnMenuItemClickListener(item -> {
                callbacks.onLocalControlActivated();
                switch (item.getItemId()) {
                    case 1: callbacks.onSettingsRequested(); break;
                    case 2: callbacks.onOrientationRequested(); break;
                    case 3: callbacks.onKeyboardRequested(); break;
                    case 4: if (options.pointerActions()) callbacks.onPointerToggle(); else callbacks.onSettingsRequested(); break;
                    case 5: callbacks.onScreenCountRequested(screenCount + 1); break;
                    case 6: callbacks.onScreenCountRequested(screenCount - 1); break;
                    case 7: callbacks.onDisconnectRequested(); break;
                    case 8: callbacks.onAboutRequested(); break;
                    default: return false;
                }
                return true;
            }); menu.show();
        });
        // Disconnect and More remain fixed when the row overflows.
        footer = new FrameLayout(activity);
        footer.setBackground(QuestUi.outlined(activity, QuestUi.color(activity, R.color.quest_panel), 16, QuestUi.color(activity, R.color.quest_border), 1));
        params.setMarginStart(QuestUi.dp(activity, 8)); params.setMarginEnd(QuestUi.dp(activity, 8)); params.bottomMargin = QuestUi.dp(activity, 8);
        disconnect.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED);
        int disconnectWidth = Math.max(QuestUi.dp(activity, 132), disconnect.getMeasuredWidth());
        more.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED);
        int moreWidth = QuestUi.dp(activity, 48);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(-1, -1); scrollParams.setMarginEnd(disconnectWidth + moreWidth + QuestUi.dp(activity, 12));
        footer.addView(scroll, scrollParams);
        TextView compact = QuestUi.text(activity, primary ? "Screens" : "Monitor", 14, QuestUi.color(activity, R.color.quest_muted));
        compact.setGravity(Gravity.CENTER_VERTICAL); compact.setSingleLine(true); compact.setEllipsize(android.text.TextUtils.TruncateAt.END);
        compact.setPadding(QuestUi.dp(activity, 12), 0, 0, 0); compact.setVisibility(android.view.View.INVISIBLE);
        compact.setContentDescription("Open More options for all monitor controls");
        footer.addView(compact, new FrameLayout.LayoutParams(scrollParams));
        // Collapse the entire row before a half-button is exposed. More contains every action.
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            boolean overflow = controls.getMeasuredWidth() > scroll.getWidth();
            scroll.setVisibility(overflow ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
            compact.setVisibility(overflow ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        });
        FrameLayout.LayoutParams moreParams = new FrameLayout.LayoutParams(moreWidth, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        moreParams.setMarginEnd(disconnectWidth + QuestUi.dp(activity, 8)); footer.addView(more, moreParams);
        FrameLayout.LayoutParams disconnectParams = new FrameLayout.LayoutParams(disconnectWidth, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        disconnectParams.setMarginEnd(QuestUi.dp(activity, 4)); footer.addView(disconnect, disconnectParams);
        root.addView(footer, params);
        setScreenCount(1, 1); setInputOptions(new AppSettings(activity).inputOptions());
    }
    android.view.View view() { return footer; }
    void setOrientation(int width, int height, boolean pending) {
        orientation.setText(pending ? "Changing display…" : "Display");
        orientation.setContentDescription(pending ? "Changing virtual desktop orientation" : "Desktop orientation and source information"
            + (width > 0 ? ". " + width + " by " + height + ". " + (height > width ? "Portrait" : "Landscape") : ""));
    }
    static int height(android.content.Context context) {
        return QuestUi.dp(context, Math.max(68, 32 + 22 * context.getResources().getConfiguration().fontScale));
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
    static void styleStatus(TextView text) { text.setTextSize(13); text.setTextColor(QuestUi.color(text.getContext(), R.color.quest_muted)); text.setPadding(QuestUi.dp(text.getContext(), 16), QuestUi.dp(text.getContext(), 10), QuestUi.dp(text.getContext(), 16), QuestUi.dp(text.getContext(), 10)); }
}
