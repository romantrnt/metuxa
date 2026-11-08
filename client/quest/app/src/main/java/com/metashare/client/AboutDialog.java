package com.metashare.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Product information shared by the launcher and monitor windows. */
final class AboutDialog {
    static AlertDialog show(Activity activity) {
        LinearLayout content = QuestUi.column(activity);
        int pad = QuestUi.dp(activity, 20); content.setPadding(pad, pad, pad, pad);
        ImageView tux = new ImageView(activity); tux.setImageResource(R.drawable.metuxa_tux);
        tux.setContentDescription("Tux holding the Meta infinity symbol"); tux.setAdjustViewBounds(true);
        LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(QuestUi.dp(activity, 112), QuestUi.dp(activity, 112));
        art.gravity = Gravity.CENTER_HORIZONTAL; content.addView(tux, art); QuestUi.space(content, 12);
        TextView brand = QuestUi.brand(activity); brand.setGravity(Gravity.CENTER); content.addView(brand);
        QuestUi.space(content, 16);
        note(content, "Your Linux desktop on Meta Quest.");
        note(content, "Share up to three monitors over your local network, with optional sound and remote input.");
        QuestUi.space(content, 12);
        note(content, "Based on MetaShare by makemake. Project source is licensed under MIT.");
        QuestUi.space(content, 16);
        link(content, activity, "GitHub", UpdateManifest.REPOSITORY);
        link(content, activity, "Licenses and credits", UpdateManifest.REPOSITORY + "/blob/main/THIRD-PARTY-NOTICES.md");
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("About metuxa").setView(scroll).setPositiveButton("Close", null).create();
        dialog.show(); return dialog;
    }
    private static void note(LinearLayout parent, String text) {
        parent.addView(QuestUi.text(parent.getContext(), text, 14, QuestUi.color(parent.getContext(), R.color.quest_muted)));
        QuestUi.space(parent, 8);
    }
    private static void link(LinearLayout parent, Activity activity, String label, String url) {
        Button button = QuestUi.button(activity, label, false);
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2); row.bottomMargin = QuestUi.dp(activity, 8); parent.addView(button, row);
        button.setOnClickListener(v -> {
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (ActivityNotFoundException e) { Toast.makeText(activity, "No browser is available to open this link.", Toast.LENGTH_LONG).show(); }
        });
    }
    private AboutDialog() {}
}
