package com.metashare.client;

import android.app.AlertDialog;
import android.app.Activity;
import android.graphics.Rect;
import android.widget.FrameLayout;
import android.content.Intent;
import android.os.Looper;
import org.robolectric.Shadows;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ThemeRenderTest {
    @Test public void wideLauncher() throws Exception { exercise("wide", 1200, 1000, 1f); }
    @Test public void narrowLauncher() throws Exception { exercise("narrow", 480, 1000, 1f); }
    @Test public void largeFontLauncher() throws Exception { exercise("large-font", 360, 800, 1.5f); }

    @Test public void toolbarLayoutsAndActions() throws Exception {
        for (int width : new int[] { 800, 360 }) {
            RuntimeEnvironment.setQualifiers("w" + width + "dp-h200dp-mdpi");
            RuntimeEnvironment.setFontScale(width == 360 ? 1.5f : 1f);
            ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
            Activity activity = controller.get(); activity.setTheme(R.style.MetuxaTheme); controller.create();
            new AppSettings(activity).preferences.edit().clear().commit();
            FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(QuestUi.BACKGROUND); activity.setContentView(root);
            int[] calls = new int[5];
            QuestToolbar toolbar = new QuestToolbar(activity, root, true, new QuestToolbar.Callbacks() {
                public void onScreenCountRequested(int count) { calls[0] = count; }
                public void onKeyboardRequested() { calls[1]++; }
                public void onPointerToggle() { calls[2]++; }
                public void onSettingsRequested() { calls[3]++; }
                public void onDisconnectRequested() { calls[4]++; }
            });
            controller.start().resume().visible(); toolbar.setScreenCount(2, 3);
            layout(root, width, QuestToolbar.height(activity) + 16);
            save(root, "toolbar-" + width);
            Button disconnect = find(root, "Disconnect", Button.class); assertNotNull(disconnect);
            Rect bounds = new Rect(0, 0, disconnect.getWidth(), disconnect.getHeight()); root.offsetDescendantRectToMyCoords(disconnect, bounds);
            assertTrue("Disconnect stays visible", bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= root.getHeight());
            find(root, "+", Button.class).performClick(); assertEquals(3, calls[0]);
            find(root, "−", Button.class).performClick(); assertEquals(1, calls[0]);
            find(root, "Keyboard", Button.class).performClick(); assertEquals(1, calls[1]);
            toolbar.setInputOptions(InputOptions.preset(InputOptions.VIEW_ONLY));
            assertFalse(find(root, "Keyboard off", Button.class).isEnabled());
            find(root, "Pointer settings", Button.class).performClick(); assertEquals(1, calls[3]);
            disconnect.performClick(); assertEquals(1, calls[4]);
            layout(root, width, QuestToolbar.height(activity) + 16); save(root, "toolbar-view-only-" + width);
            controller.pause().stop().destroy();
        }
    }

    private void exercise(String name, int width, int height, float fontScale) throws Exception {
        RuntimeEnvironment.setQualifiers("w" + width + "dp-h" + height + "dp-mdpi");
        RuntimeEnvironment.setFontScale(fontScale);
        new AppSettings(RuntimeEnvironment.getApplication()).preferences.edit().clear().commit();
        ConnectionCoordinator.INSTANCE.disconnect();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class,
            new Intent(RuntimeEnvironment.getApplication(), MainActivity.class).putExtra("manage", true));
        MainActivity activity = controller.create().get();
        // The real launcher resumes, but no DiscoveryClient or media/network session is started.
        ReflectionHelpers.setField(activity, "scanned", true);
        controller.start().resume().visible();
        assertNull(ReflectionHelpers.getField(activity, "discovery"));
        assertNull(ConnectionCoordinator.INSTANCE.current());
        ScrollView scroll = (ScrollView) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        layout(scroll, width, height);
        save(scroll, name + "-viewport");
        save(scroll.getChildAt(0), name + "-full-page");
        inspectControls(scroll, width);

        Button viewOnly = find(scroll, "View only", Button.class);
        assertNotNull(viewOnly); assertTrue(viewOnly.performClick());
        assertEquals("View only", new AppSettings(activity).inputOptions().presetLabel());
        assertTrue(viewOnly.isSelected());
        assertFalse(((CheckBox) ReflectionHelpers.getField(activity, "keyboardInput")).isChecked());
        Button keyboard = find(scroll, "Keyboard only", Button.class); keyboard.performClick();
        assertEquals("Keyboard only", new AppSettings(activity).inputOptions().presetLabel());
        assertTrue(keyboard.isSelected()); assertFalse(viewOnly.isSelected());
        find(scroll, "Full remote", Button.class).performClick();
        assertEquals("Full remote", new AppSettings(activity).inputOptions().presetLabel());

        if (name.equals("wide")) {
            android.content.res.Configuration configuration = new android.content.res.Configuration(activity.getResources().getConfiguration());
            configuration.screenWidthDp = 480; activity.onConfigurationChanged(configuration);
            layout(scroll, 480, height); inspectControls(scroll, 480); save(scroll, "wide-resized-narrow");
            configuration.screenWidthDp = width; activity.onConfigurationChanged(configuration);
            layout(scroll, width, height); inspectControls(scroll, width); save(scroll, "wide-resized-back");
            assertEquals("Full remote", new AppSettings(activity).inputOptions().presetLabel());
        }
        find(scroll, "Choose allowed pointer devices", Button.class).performClick();
        AlertDialog devices = ShadowAlertDialog.getLatestAlertDialog(); assertTrue(devices.isShowing());
        layout(devices.getWindow().getDecorView(), Math.min(width - 32, 600), 540);
        save(devices.getWindow().getDecorView(), name + "-devices-dialog");
        devices.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertFalse(devices.isShowing());

        ((EditText) ReflectionHelpers.getField(activity, "host")).setText("192.0.2.10");
        find(scroll, "Forget", Button.class).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertTrue(dialog.isShowing());
        View decor = dialog.getWindow().getDecorView(); layout(decor, Math.min(width - 32, 600), 460);
        save(decor, name + "-forget-dialog");
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertFalse(dialog.isShowing());
        assertNull(ConnectionCoordinator.INSTANCE.current());

        find(scroll, "Inspect / reset metadata", Button.class).performClick();
        AlertDialog metadata = ShadowAlertDialog.getLatestAlertDialog(); assertTrue(metadata.isShowing());
        layout(metadata.getWindow().getDecorView(), Math.min(width - 32, 640), 600);
        save(metadata.getWindow().getDecorView(), name + "-metadata-dialog");
        metadata.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); assertTrue(metadata.isShowing());
        metadata.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertFalse(metadata.isShowing());
        assertNull(ReflectionHelpers.getField(activity, "diagnosticsDialog"));
        assertNull(ReflectionHelpers.getField(activity, "discovery"));
        controller.pause().stop().destroy();
        System.out.println("Rendered real Android Views: " + name + " " + width + "x" + height + " font=" + fontScale);
    }
    private void inspectControls(View view, int width) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof Button) {
            assertTrue("Small target: " + ((Button) view).getText(), view.getHeight() >= 48);
            assertTrue("Control wider than viewport: " + ((Button) view).getText(), view.getWidth() <= width);
            TextView text = (TextView) view;
            if (text.getLayout() != null) assertTrue("Vertically clipped button: " + text.getText(),
                text.getLayout().getHeight() + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom() <= text.getHeight());
        }
        if (view instanceof LinearLayout && view.getClass().getSimpleName().equals("AdaptiveActions")) {
            LinearLayout group = (LinearLayout) view;
            View previous = null;
            for (int i=0; i<group.getChildCount(); i++) {
                View child = group.getChildAt(i); if (child.getVisibility() != View.VISIBLE) continue;
                if (previous != null) assertTrue("Missing adaptive action gap", group.getOrientation() == LinearLayout.VERTICAL
                    ? child.getTop() - previous.getBottom() >= 8 : child.getLeft() - previous.getRight() >= 8);
                previous = child;
            }
        }
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) inspectControls(((ViewGroup)view).getChildAt(i), width);
    }
    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
    private static void save(View view, String name) throws Exception {
        assertTrue("Nonzero render size", view.getWidth() > 0 && view.getHeight() > 0);
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(QuestUi.BACKGROUND); view.draw(canvas);
        File file = new File(System.getProperty("metuxa.ui.qa.output"), name + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); }
        bitmap.recycle();
    }
    private static <T extends TextView> T find(View view, String text, Class<T> type) {
        if (type.isInstance(view) && text.contentEquals(((TextView)view).getText())) return type.cast(view);
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            T result = find(((ViewGroup)view).getChildAt(i), text, type); if (result != null) return result;
        }
        return null;
    }
}
