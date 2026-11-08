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
            int[] calls = new int[6];
            QuestToolbar toolbar = new QuestToolbar(activity, root, true, new QuestToolbar.Callbacks() {
                public void onScreenCountRequested(int count) { calls[0] = count; }
                public void onKeyboardRequested() { calls[1]++; }
                public void onPointerToggle() { calls[2]++; }
                public void onSettingsRequested() { calls[3]++; }
                public void onDisconnectRequested() { calls[4]++; }
                public void onRotateRequested() { calls[5]++; }
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
            Button more = find(root,"More",Button.class); assertNotNull(more); more.performClick();
            android.widget.PopupMenu menu = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu(); assertNotNull(menu);
            org.robolectric.shadows.ShadowPopupMenu menuShadow = Shadows.shadowOf(menu);
            menuShadow.getOnMenuItemClickListener().onMenuItemClick(menu.getMenu().findItem(1)); assertEquals(2,calls[3]);
            menuShadow.getOnMenuItemClickListener().onMenuItemClick(menu.getMenu().findItem(2)); assertEquals(1,calls[5]); menu.dismiss();
            layout(root, width, QuestToolbar.height(activity) + 16); save(root, "toolbar-view-only-" + width);
            controller.pause().stop().destroy();
        }
    }

    @Test public void settingsPersistAndThemesRender() throws Exception {
        for (int theme : new int[] {0, 1, 2}) {
            RuntimeEnvironment.setQualifiers("w480dp-h900dp-notnight-mdpi"); RuntimeEnvironment.setFontScale(1f);
            AppSettings preferences = new AppSettings(RuntimeEnvironment.getApplication()); preferences.preferences.edit().clear().commit(); preferences.integer("theme", theme);
            ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, new Intent(RuntimeEnvironment.getApplication(), MainActivity.class).putExtra("manage", true));
            MainActivity activity = controller.create().get(); ReflectionHelpers.setField(activity, "scanned", true); controller.start().resume().visible();
            View main = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0); layout(main,480,900);
            int expected = theme == 0 ? 0xff181818 : 0xfff3f3f3;
            assertEquals("Theme resources", expected, QuestUi.color(activity,R.color.quest_background));
            find(main,"Settings",Button.class).performClick(); AlertDialog settings = ShadowAlertDialog.getLatestAlertDialog();
            layout(settings.getWindow().getDecorView(),448,840); save(settings.getWindow().getDecorView(),"settings-theme-"+theme);
            CheckBox awake = find(settings.getWindow().getDecorView(),"Keep the display awake while connected",CheckBox.class); awake.performClick();
            assertFalse(new AppSettings(activity).keepAwake());
            CheckBox updates = find(settings.getWindow().getDecorView(),"Check for updates when the launcher opens",CheckBox.class); updates.performClick();
            CheckBox downloads = find(settings.getWindow().getDecorView(),"Download verified updates automatically",CheckBox.class); assertTrue(downloads.isEnabled());
            updates.performClick(); assertFalse(downloads.isEnabled());
            settings.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertFalse(settings.isShowing());
            AppSettings persisted = new AppSettings(activity); persisted.rotation("192.0.2.1:7878",0,270); persisted.rotation("192.0.2.1:7878",1,90);
            assertEquals(270,new AppSettings(activity).rotation("192.0.2.1:7878",0)); assertEquals(90,persisted.rotation("192.0.2.1:7878",1)); assertEquals(0,persisted.rotation("192.0.2.2:7878",0));
            controller.pause().stop().destroy();
        }
    }
    @Test public void formSurvivesThemeRecreation() {
        RuntimeEnvironment.setQualifiers("w480dp-h900dp-notnight-mdpi"); RuntimeEnvironment.setFontScale(1f);
        AppSettings settings = new AppSettings(RuntimeEnvironment.getApplication()); settings.preferences.edit().clear().commit(); settings.flag("discover_on_open",false);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class,new Intent(RuntimeEnvironment.getApplication(),MainActivity.class).putExtra("manage",true)).create().start().resume().visible();
        MainActivity first=controller.get(); ((EditText)ReflectionHelpers.getField(first,"host")).setText("192.0.2.7"); ((EditText)ReflectionHelpers.getField(first,"token")).setText("0123456789abcdef0123456789abcdef");
        settings.integer("theme",1);controller.recreate();MainActivity recreated=controller.get();
        assertEquals("192.0.2.7",((EditText)ReflectionHelpers.getField(recreated,"host")).getText().toString());
        assertEquals("0123456789abcdef0123456789abcdef",((EditText)ReflectionHelpers.getField(recreated,"token")).getText().toString());
        assertEquals(0xfff3f3f3,QuestUi.color(recreated,R.color.quest_background));assertNull(ConnectionCoordinator.INSTANCE.current());
        controller.pause().stop().destroy();
    }

    @Test public void remoteTouchRestoresKeyboardFocus() {
        RuntimeEnvironment.setQualifiers("w600dp-h400dp-mdpi");RuntimeEnvironment.setFontScale(1f);
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).create();Activity activity=controller.get();
        new AppSettings(activity).preferences.edit().clear().commit();FrameLayout root=new FrameLayout(activity);activity.setContentView(root);
        // ShadowActivity.getCurrentFocus is a stored stub, unlike Window.findFocus.
        // Keep it synchronized with genuine View focus changes for this controller test.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((old,next)->Shadows.shadowOf(activity).setCurrentFocus(next));
        RemoteSurfaceView surface=new RemoteSurfaceView(activity);surface.setFocusableInTouchMode(true);root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        Button local=QuestUi.button(activity,"Local control",false);local.setFocusableInTouchMode(true);root.addView(local,new FrameLayout.LayoutParams(140,48,android.view.Gravity.TOP));
        int[] keys={0};RemoteInputController input=new RemoteInputController(activity,root,surface,new RemoteInputController.Listener(){
            public void onPointerInput(RemoteInputController.PointerEvent e){} public void onInputPolicy(InputOptions o){} public void onTextInput(String text){}
            public void onKeyInput(int key,boolean pressed){keys[0]++;} public void releasePointer(){} public void releaseAll(){}
        });controller.start().resume().visible().windowFocusChanged(true);input.setResumed(true);input.setWindowFocused(true);layout(root,600,400);local.requestFocus();assertSame(local,activity.getCurrentFocus());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(5));long now=android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,300,200,0);root.dispatchTouchEvent(down);down.recycle();assertSame(surface,activity.getCurrentFocus());
        android.view.KeyEvent key=new android.view.KeyEvent(now,now+1,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_A,0);
        assertTrue(input.handleKey(key));assertEquals(1,keys[0]);
        input.showKeyboard();View keyboard=activity.getCurrentFocus();assertNotSame(surface,keyboard);
        down=android.view.MotionEvent.obtain(now+2,now+2,android.view.MotionEvent.ACTION_DOWN,300,200,0);root.dispatchTouchEvent(down);down.recycle();assertSame(keyboard,activity.getCurrentFocus());
        input.release();controller.pause().stop().destroy();
    }

    @Test public void systemThemeFollowsDayAndNight() {
        RuntimeEnvironment.setQualifiers("w480dp-h900dp-notnight-mdpi");RuntimeEnvironment.setFontScale(1f);
        AppSettings settings = new AppSettings(RuntimeEnvironment.getApplication());settings.preferences.edit().clear().commit();settings.integer("theme",2);settings.flag("discover_on_open",false);
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(RuntimeEnvironment.getApplication(),MainActivity.class).putExtra("manage",true)).create().start().resume().visible();
        for(int mode:new int[]{android.content.res.Configuration.UI_MODE_NIGHT_YES,android.content.res.Configuration.UI_MODE_NIGHT_NO}) {
            android.content.res.Configuration config=new android.content.res.Configuration(controller.get().getResources().getConfiguration());config.uiMode=(config.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)|mode;
            controller.configurationChange(config);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(mode==android.content.res.Configuration.UI_MODE_NIGHT_YES?0xff181818:0xfff3f3f3,QuestUi.color(controller.get(),R.color.quest_background));
        }
        controller.pause().stop().destroy();
    }

    @Test public void rotatedSurfaceMapsRealAndroidTouchAndHover() {
        RuntimeEnvironment.setQualifiers("w600dp-h500dp-mdpi"); RuntimeEnvironment.setFontScale(1f);
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create().start().resume().visible(); Activity activity = controller.get();
        FrameLayout root = new FrameLayout(activity); activity.setContentView(root); RemoteSurfaceView surface = new RemoteSurfaceView(activity); surface.setFocusableInTouchMode(true); surface.setClickable(true);
        root.addView(surface,new FrameLayout.LayoutParams(160,90,android.view.Gravity.CENTER));
        float[] local = new float[2];
        surface.setOnTouchListener((v,e)->{local[0]=e.getX()/v.getWidth();local[1]=e.getY()/v.getHeight();return true;});
        surface.setOnHoverListener((v,e)->{local[0]=e.getX()/v.getWidth();local[1]=e.getY()/v.getHeight();return true;});
        for(int angle : new int[]{0,90,180,270}) for(int width : new int[]{600,300}) {
            surface.setMonitorRotation(angle); int[] size=MonitorGeometry.fit(width,500,1600,900,angle);
            surface.setLayoutParams(new FrameLayout.LayoutParams(size[0],size[1],android.view.Gravity.CENTER)); layout(root,width,500);
            float sx=.23f,sy=.67f; int visibleWidth=angle%180==0?surface.getWidth():surface.getHeight(),visibleHeight=angle%180==0?surface.getHeight():surface.getWidth();
            float x=width/2f+(sx-.5f)*visibleWidth,y=250+(sy-.5f)*visibleHeight; float[] expected=MonitorGeometry.inverse(sx,sy,angle);
            android.view.MotionEvent touch=android.view.MotionEvent.obtain(1,2,android.view.MotionEvent.ACTION_DOWN,x,y,0); touch.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue(root.dispatchTouchEvent(touch)); touch.recycle(); assertEquals(expected[0],local[0],.006f);assertEquals(expected[1],local[1],.006f);
            android.view.MotionEvent up=android.view.MotionEvent.obtain(1,3,android.view.MotionEvent.ACTION_UP,x,y,0);root.dispatchTouchEvent(up);up.recycle();
            android.view.MotionEvent hover=pointerHover(x,y);

            assertTrue("Hover dispatched at " + angle + " degrees",root.dispatchGenericMotionEvent(hover));hover.recycle();assertEquals(expected[0],local[0],.006f);assertEquals(expected[1],local[1],.006f);
        }
        controller.pause().stop().destroy();
    }
    @Test public void overlayHandleTimersAndTouchFallback() throws Exception {
        RuntimeEnvironment.setQualifiers("w600dp-h400dp-mdpi");RuntimeEnvironment.setFontScale(1f);
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).create().start().resume().visible();Activity activity=controller.get();
        AppSettings prefs=new AppSettings(activity);prefs.preferences.edit().clear().commit();prefs.integer("hide_delay",2);
        int[] localInput={0},remoteTouches={0}; PanelOverlay root=new PanelOverlay(activity,()->localInput[0]++);activity.setContentView(root);
        View video=new View(activity);video.setOnTouchListener((v,e)->{remoteTouches[0]++;return true;});root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        TextView top=QuestUi.text(activity,"Monitor 1 · layout test",14,QuestUi.color(activity,R.color.quest_text));root.addView(top,new FrameLayout.LayoutParams(-1,64,android.view.Gravity.TOP));
        QuestToolbar toolbar = new QuestToolbar(activity,root,true,new QuestToolbar.Callbacks() {
            public void onScreenCountRequested(int count){} public void onKeyboardRequested(){} public void onPointerToggle(){} public void onSettingsRequested(){} public void onDisconnectRequested(){}
        });
        View bottom=toolbar.view();root.panels(top,bottom);layout(root,600,400);
        assertEquals(400,video.getHeight());assertEquals(View.VISIBLE,bottom.getVisibility());
        Button settingsButton = find(root,"Settings",Button.class); settingsButton.setFocusableInTouchMode(true); int beforeFocus=localInput[0]; settingsButton.requestFocus();
        assertTrue("Native control focus releases remote input",localInput[0]>beforeFocus);settingsButton.clearFocus();root.setFocusableInTouchMode(true);root.requestFocus();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3));assertEquals(View.INVISIBLE,bottom.getVisibility());
        Button handle=find(root,"Controls",Button.class);assertNotNull(handle);assertTrue(handle.getHeight()>=48);handle.performClick();assertEquals(View.VISIBLE,bottom.getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10));assertEquals(View.VISIBLE,bottom.getVisibility());
        layout(root,600,400);save(root,"monitor-controls-pinned");find(root,"Hide controls",Button.class).performClick();assertEquals(View.INVISIBLE,bottom.getVisibility());
        android.view.MotionEvent event=android.view.MotionEvent.obtain(1,2,android.view.MotionEvent.ACTION_DOWN,200,200,0);root.dispatchTouchEvent(event);event.recycle();assertEquals(1,remoteTouches[0]);
        prefs.integer("top_bar",PanelVisibility.HOVER);root.configure();android.view.MotionEvent hover=pointerHover(10,2);root.dispatchGenericMotionEvent(hover);hover.recycle();assertEquals(View.VISIBLE,top.getVisibility());
        root.resumed(false);root.close();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(20));controller.pause().stop().destroy();
    }

    private static android.view.MotionEvent pointerHover(float x,float y) {
        android.view.MotionEvent.PointerProperties properties = new android.view.MotionEvent.PointerProperties(); properties.id=0;properties.toolType=android.view.MotionEvent.TOOL_TYPE_STYLUS;
        android.view.MotionEvent.PointerCoords coordinates = new android.view.MotionEvent.PointerCoords();coordinates.x=x;coordinates.y=y;
        // Robolectric stubs the native mouse cursor position to (0,0). Stylus hover
        // follows the same ViewGroup inverse transform using the event coordinates.
        return android.view.MotionEvent.obtain(4,5,android.view.MotionEvent.ACTION_HOVER_MOVE,1,new android.view.MotionEvent.PointerProperties[]{properties},new android.view.MotionEvent.PointerCoords[]{coordinates},0,0,1,1,1,0,android.view.InputDevice.SOURCE_STYLUS,0);
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
