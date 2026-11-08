package com.metashare.client;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Bundle;

/** Public window-manager hints. Horizon may keep its own bounds or restored size. */
final class PanelWindow {
    static Bundle launchOptions(Activity activity, int width, int height) {
        return ActivityOptions.makeBasic().setLaunchBounds(launchBounds(activity, width, height)).toBundle();
    }
    static Rect launchBounds(Activity activity, int width, int height) {
        int[] size = MonitorGeometry.panelSize(width, height);
        Rect screen = new Rect(activity.getWindowManager().getMaximumWindowMetrics().getBounds());
        if (screen.width() <= 0 || screen.height() <= 0) {
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            screen.set(0, 0, Math.max(1, metrics.widthPixels), Math.max(1, metrics.heightPixels));
        }
        int w = QuestUi.dp(activity, size[0]), h = QuestUi.dp(activity, size[1]);
        // Bounds are screen-space pixels, not video pixels. Keep them within the display.
        double scale = Math.min(1d, Math.min((double) screen.width() / w, (double) screen.height() / h));
        w = Math.max(1, (int) (w * scale)); h = Math.max(1, (int) (h * scale));
        int left = screen.left + (screen.width() - w) / 2, top = screen.top + (screen.height() - h) / 2;
        return new Rect(left, top, left + w, top + h);
    }
    static void followStream(Activity activity, int width, int height) {
        if (width <= 0 || height <= 0) return;
        int orientation = width >= height ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (activity.getRequestedOrientation() != orientation) activity.setRequestedOrientation(orientation);
    }
    private PanelWindow() {}
}
