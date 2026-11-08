package com.metashare.client;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Switch;

/** Native accessible switch with neutral GNOME-like track and thumb. */
final class QuestSwitch extends Switch {
    QuestSwitch(Context context) {
        super(context);
        setShowText(false); setSplitTrack(false); setSwitchMinWidth(QuestUi.dp(context, 46));
        setSwitchPadding(QuestUi.dp(context, 16)); setThumbTintList(null); setTrackTintList(null);
        StateListDrawable track = new StateListDrawable(), thumb = new StateListDrawable();
        track.addState(new int[] {-android.R.attr.state_enabled}, shape(context, R.color.quest_border, 46, 28));
        track.addState(new int[] {android.R.attr.state_checked}, shape(context, R.color.quest_accent, 46, 28));
        track.addState(new int[] {}, shape(context, R.color.quest_outline, 46, 28));
        thumb.addState(new int[] {-android.R.attr.state_enabled}, shape(context, R.color.quest_disabled, 22, 22));
        thumb.addState(new int[] {android.R.attr.state_checked}, shape(context, R.color.quest_ink, 22, 22));
        thumb.addState(new int[] {}, shape(context, R.color.quest_text, 22, 22));
        setTrackDrawable(track); setThumbDrawable(thumb); QuestUi.styleToggle(this);
    }
    private static GradientDrawable shape(Context context, int color, int width, int height) {
        GradientDrawable shape = QuestUi.background(context, QuestUi.color(context, color), height / 2);
        shape.setSize(QuestUi.dp(context, width), QuestUi.dp(context, height)); return shape;
    }
}
