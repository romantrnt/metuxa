package com.metashare.client;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared neutral palette and accessible native control treatments. */
final class QuestUi {
    // Matched against colors.xml by the theme regression checks.
    static final int BACKGROUND = 0xff181818, PANEL = 0xff242424, FIELD = 0xff303030;
    static final int TEXT = 0xfff2f2f2, MUTED = 0xffbdbdbd, ACCENT = 0xffdedede, INK = 0xff202020;
    static final int BORDER = 0xff3e3e3e, OUTLINE = 0xff858585, HOVER = 0xff414141, DISABLED = 0xff858585;
    static final int ERROR = 0xffffb4a8;
    static Context themedContext(Context base) {
        AppSettings settings = new AppSettings(base);
        android.content.res.Configuration config = new android.content.res.Configuration(base.getResources().getConfiguration());
        if (settings.theme() != 2) config.uiMode = (config.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            | (settings.theme() == 1 ? android.content.res.Configuration.UI_MODE_NIGHT_NO : android.content.res.Configuration.UI_MODE_NIGHT_YES);
        config.fontScale *= settings.textScale() / 100f;
        return base.createConfigurationContext(config);
    }
    static int color(Context context, int resource) { return context.getColor(resource); }
    static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static GradientDrawable background(Context context, int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(context, radius)); return d;
    }
    static GradientDrawable outlined(Context context, int color, int radius, int stroke, int width) {
        GradientDrawable d = background(context, color, radius); d.setStroke(dp(context, width), stroke); return d;
    }
    static Drawable buttonBackground(Context context, boolean primary) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { -android.R.attr.state_enabled }, outlined(context, color(context, R.color.quest_field), 10, color(context, R.color.quest_border), 1));
        states.addState(new int[] { android.R.attr.state_selected, android.R.attr.state_focused }, outlined(context, color(context, R.color.quest_accent), 10, color(context, R.color.quest_ink), 2));
        states.addState(new int[] { android.R.attr.state_selected, android.R.attr.state_hovered }, outlined(context, color(context, R.color.quest_text), 10, color(context, R.color.quest_ink), 1));
        states.addState(new int[] { android.R.attr.state_focused }, outlined(context, primary ? color(context, R.color.quest_accent) : color(context, R.color.quest_hover), 10, primary ? color(context, R.color.quest_ink) : color(context, R.color.quest_accent), 2));
        states.addState(new int[] { android.R.attr.state_hovered }, outlined(context, primary ? color(context, R.color.quest_text) : color(context, R.color.quest_hover), 10, primary ? color(context, R.color.quest_ink) : color(context, R.color.quest_accent), 1));
        states.addState(new int[] { android.R.attr.state_selected }, outlined(context, color(context, R.color.quest_accent), 10, color(context, R.color.quest_accent), 1));
        states.addState(new int[] {}, outlined(context, primary ? color(context, R.color.quest_accent) : color(context, R.color.quest_field), 10, primary ? color(context, R.color.quest_accent) : color(context, R.color.quest_border), 1));
        return new RippleDrawable(ColorStateList.valueOf(primary ? 0x26181818 : 0x24ffffff), states, null);
    }
    static TextView text(Context context, String value, int sp, int color) {
        TextView t = new TextView(context); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        t.setLineSpacing(dp(context, 3), 1); return t;
    }
    static Button button(Context context, String label, boolean primary) {
        Button b = new Button(context); b.setText(label); b.setAllCaps(false); b.setTextSize(15); b.setLetterSpacing(0);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(new ColorStateList(new int[][] { { -android.R.attr.state_enabled }, { android.R.attr.state_selected }, {} },
            new int[] { color(context, R.color.quest_disabled), color(context, R.color.quest_ink), primary ? color(context, R.color.quest_ink) : color(context, R.color.quest_text) }));
        b.setMinHeight(dp(context, 48)); b.setMinimumHeight(dp(context, 48));
        b.setMinWidth(dp(context, 48)); b.setMinimumWidth(dp(context, 48));
        b.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        b.setBackgroundTintList(null); b.setBackground(buttonBackground(context, primary)); b.setStateListAnimator(null);
        b.setContentDescription(label); return b;
    }
    static void styleToggle(CompoundButton button) {
        Context context = button.getContext();
        button.setButtonTintList(context.getColorStateList(R.color.quest_control_tint));
        button.setTextColor(context.getColorStateList(R.color.quest_text_states)); button.setTextSize(15);
        button.setMinHeight(dp(context, 56)); button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        button.setBackground(buttonBackground(context, false));
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2); row.bottomMargin = dp(context, 4); button.setLayoutParams(row);
        button.setLineSpacing(dp(context, 2), 1);
    }
    static LinearLayout column(Context context) { LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context context) { LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static LinearLayout actions(Context context) { return new AdaptiveActions(context); }
    static void space(LinearLayout parent, int height) { View v = new View(parent.getContext()); parent.addView(v, new LinearLayout.LayoutParams(1, dp(parent.getContext(), height))); }
    static TextView heading(LinearLayout parent, String title) {
        Context context = parent.getContext();
        TextView t = text(parent.getContext(), title, 20, color(context, R.color.quest_text)); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setAccessibilityHeading(true); parent.addView(t); space(parent, 8); return t;
    }
    static void divider(LinearLayout parent) {
        Context context = parent.getContext();
        space(parent, 22); View line = new View(parent.getContext()); line.setBackgroundColor(color(context, R.color.quest_border));
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(parent.getContext(), 1))); space(parent, 22);
    }
    static LinearLayout disclosure(LinearLayout parent, String title) {
        Context context = parent.getContext();
        Button toggle = button(context, title, false); toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.quest_chevron, 0);
        parent.addView(toggle, new LinearLayout.LayoutParams(-1, -2)); space(parent, 8);
        LinearLayout content = column(context); content.setVisibility(View.GONE); parent.addView(content);
        toggle.setContentDescription(title + ". Expand section");
        toggle.setOnClickListener(v -> {
            boolean expanded = content.getVisibility() != View.VISIBLE;
            content.setVisibility(expanded ? View.VISIBLE : View.GONE);
            toggle.setSelected(expanded); toggle.getCompoundDrawablesRelative()[2].mutate().setTint(color(context, expanded ? R.color.quest_ink : R.color.quest_muted)); toggle.setContentDescription(title + (expanded ? ". Collapse section" : ". Expand section"));
        });
        return content;
    }
    static void choice(LinearLayout parent, String title, String[] labels, int selected, java.util.function.IntConsumer change) {
        Context context = parent.getContext();
        Button button = button(context, title + "\n" + labels[selected], false);
        button.setText(choiceLabel(context, title, labels[selected]));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.quest_chevron, 0);
        button.setCompoundDrawablePadding(dp(context, 12)); button.setContentDescription(title + ": " + labels[selected]);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(context, 6); parent.addView(button, params);
        int[] current = {selected};
        button.setOnClickListener(v -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(context, button);
            for (int i = 0; i < labels.length; i++) menu.getMenu().add(1, i, i, labels[i]).setCheckable(true).setChecked(i == current[0]);
            menu.getMenu().setGroupCheckable(1, true, true);
            menu.setOnMenuItemClickListener(item -> {
                current[0] = item.getItemId(); button.setText(choiceLabel(context, title, labels[current[0]]));
                button.setContentDescription(title + ": " + labels[current[0]]); change.accept(current[0]); return true;
            }); menu.show();
        });
    }
    private static CharSequence choiceLabel(Context context, String title, String value) {
        android.text.SpannableString label = new android.text.SpannableString(title + "\n" + value);
        label.setSpan(new android.text.style.ForegroundColorSpan(color(context, R.color.quest_muted)), title.length() + 1, label.length(), 0);
        label.setSpan(new android.text.style.RelativeSizeSpan(.92f), title.length() + 1, label.length(), 0);
        label.setSpan(new android.text.style.TypefaceSpan("sans-serif"), title.length() + 1, label.length(), 0);
        return label;
    }
    /** Stack action groups when their actual labels/font scale do not fit. */
    private static final class AdaptiveActions extends LinearLayout {
        AdaptiveActions(Context context) { super(context); setGravity(Gravity.CENTER_VERTICAL); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int visible = 0, largest = 0, gap = dp(getContext(), 8);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); if (child.getVisibility() == View.GONE) continue;
                child.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                largest = Math.max(largest, child.getMeasuredWidth()); visible++;
            }
            boolean stacked = View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED
                && largest * visible + gap * Math.max(0, visible - 1) > available;
            setOrientation(stacked ? VERTICAL : HORIZONTAL);
            int position = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); if (child.getVisibility() == View.GONE) continue;
                LayoutParams params = (LayoutParams) child.getLayoutParams();
                params.width = stacked ? LayoutParams.MATCH_PARENT : 0;
                params.height = LayoutParams.WRAP_CONTENT; params.weight = stacked ? 0 : 1;
                params.setMarginStart(!stacked && position > 0 ? gap : 0); params.setMarginEnd(0);
                params.resolveLayoutDirection(getLayoutDirection());
                params.topMargin = stacked && position > 0 ? gap : 0; params.bottomMargin = 0; position++;
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
    private QuestUi() {}
}
