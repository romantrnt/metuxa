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
    static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static GradientDrawable background(Context context, int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(context, radius)); return d;
    }
    static GradientDrawable outlined(Context context, int color, int radius, int stroke, int width) {
        GradientDrawable d = background(context, color, radius); d.setStroke(dp(context, width), stroke); return d;
    }
    static Drawable buttonBackground(Context context, boolean primary) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { -android.R.attr.state_enabled }, outlined(context, FIELD, 10, BORDER, 1));
        states.addState(new int[] { android.R.attr.state_selected, android.R.attr.state_focused }, outlined(context, ACCENT, 10, INK, 2));
        states.addState(new int[] { android.R.attr.state_selected, android.R.attr.state_hovered }, outlined(context, TEXT, 10, INK, 1));
        states.addState(new int[] { android.R.attr.state_focused }, outlined(context, primary ? ACCENT : HOVER, 10, primary ? INK : ACCENT, 2));
        states.addState(new int[] { android.R.attr.state_hovered }, outlined(context, primary ? TEXT : HOVER, 10, primary ? INK : ACCENT, 1));
        states.addState(new int[] { android.R.attr.state_selected }, outlined(context, ACCENT, 10, ACCENT, 1));
        states.addState(new int[] {}, outlined(context, primary ? ACCENT : FIELD, 10, primary ? ACCENT : OUTLINE, 1));
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
            new int[] { DISABLED, INK, primary ? INK : TEXT }));
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
        button.setMinHeight(dp(context, 48)); button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(context, 4), dp(context, 8), dp(context, 8), dp(context, 8));
        button.setLineSpacing(dp(context, 2), 1);
    }
    static LinearLayout column(Context context) { LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context context) { LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static LinearLayout actions(Context context) { return new AdaptiveActions(context); }
    static void space(LinearLayout parent, int height) { View v = new View(parent.getContext()); parent.addView(v, new LinearLayout.LayoutParams(1, dp(parent.getContext(), height))); }
    static TextView heading(LinearLayout parent, String title) {
        TextView t = text(parent.getContext(), title, 21, TEXT); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setAccessibilityHeading(true); parent.addView(t); space(parent, 8); return t;
    }
    static void divider(LinearLayout parent) {
        space(parent, 22); View line = new View(parent.getContext()); line.setBackgroundColor(BORDER);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(parent.getContext(), 1))); space(parent, 22);
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
