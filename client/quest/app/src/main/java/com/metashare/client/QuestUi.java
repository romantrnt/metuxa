package com.metashare.client;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class QuestUi {
    static final int BACKGROUND = 0xff12263c, PANEL = 0xff1d3650, FIELD = 0xff27415b;
    static final int TEXT = 0xfff2f7fc, MUTED = 0xffb8ccdf, ACCENT = 0xffb6dff2, INK = 0xff12324a, ERROR = 0xffffb4a8;
    static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static GradientDrawable background(Context context, int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(context, radius)); return d;
    }
    static TextView text(Context context, String value, int sp, int color) {
        TextView t = new TextView(context); t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setLineSpacing(dp(context, 3), 1); return t;
    }
    static Button button(Context context, String label, boolean primary) {
        Button b = new Button(context); b.setText(label); b.setAllCaps(false); b.setTextSize(15);
        b.setTextColor(primary ? INK : TEXT); b.setMinHeight(dp(context, 48)); b.setMinimumWidth(dp(context, 48));
        b.setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 6));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? ACCENT : FIELD));
        b.setContentDescription(label); return b;
    }
    static LinearLayout column(Context context) { LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context context) { LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static void space(LinearLayout parent, int height) { android.view.View v = new android.view.View(parent.getContext()); parent.addView(v, new LinearLayout.LayoutParams(1, dp(parent.getContext(), height))); }
    static TextView heading(LinearLayout parent, String title) {
        TextView t = text(parent.getContext(), title, 21, TEXT); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); parent.addView(t); space(parent, 8); return t;
    }
    private QuestUi() {}
}
