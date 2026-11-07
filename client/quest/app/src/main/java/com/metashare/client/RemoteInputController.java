package com.metashare.client;

import android.app.Activity;
import android.graphics.Color;
import android.os.SystemClock;
import android.text.InputType;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.hardware.input.InputManager;

/** Remote content alone forwards pointer events. Native controls always remain usable. */
final class RemoteInputController {
    static final class PointerEvent {
        final int action, buttons;
        final float x, y, verticalScroll, horizontalScroll;
        PointerEvent(int action, float x, float y, float v, float h, int buttons) {
            this.action = action; this.x = x; this.y = y; verticalScroll = v; horizontalScroll = h; this.buttons = buttons;
        }
    }
    interface Listener {
        void onPointerInput(PointerEvent event);
        void onInputPolicy(InputOptions options);
        void onTextInput(String text);
        void onKeyInput(int keysym, boolean pressed);
        void releasePointer();
        void releaseAll();
    }
    private final Activity activity;
    private final RemoteSurfaceView surface;
    private final View keyboard;
    private final Listener listener;
    private final AppSettings settings;
    private final HardwareKeyLedger heldHardware = new HardwareKeyLedger();
    private final PointerOwnership pointerOwnership = new PointerOwnership();
    private final InputManager inputManager;
    private final InputManager.InputDeviceListener deviceListener = new InputManager.InputDeviceListener() {
        public void onInputDeviceAdded(int deviceId) { }
        public void onInputDeviceRemoved(int deviceId) { if (pointerOwnership.removed(deviceId)) releaseOwnedPointer(); }
        public void onInputDeviceChanged(int deviceId) {
            if (!pointerOwnership.ownsDevice(deviceId)) return;
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device == null || !pointerOwnership.matchesOwner(deviceId, device.getDescriptor()) || !allowsDevice(device)) releaseOwnedPointer();
        }
    };
    private int pointerId = -1;
    private String composition = "";
    private boolean released;
    private final InputLifecycle lifecycle = new InputLifecycle();
    private int pointerMode;
    private InputOptions options;
    private long rearmAtMs;
    private int keyboardEpoch;
    private java.util.Set<String> allowedDevices;
    RemoteInputController(Activity activity, FrameLayout root, RemoteSurfaceView surface, Listener listener) {
        this.activity = activity; this.surface = surface; this.listener = listener; settings = new AppSettings(activity);
        options = settings.inputOptions(); pointerMode = options.pointerMode; allowedDevices = pointerMode == PointerPolicy.EXCEPT_DEVICES ? settings.excludedDevices() : settings.devices();
        inputManager = (InputManager) activity.getSystemService(Activity.INPUT_SERVICE);
        if (inputManager != null) inputManager.registerInputDeviceListener(deviceListener, null);
        surface.setOnTouchListener((v, e) -> {
            boolean consumed = capturePointer(e);
            if (consumed && e.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
            return consumed;
        });
        surface.setOnGenericMotionListener((v, e) -> capturePointer(e));
        keyboard = new View(activity) {
            @Override public boolean onCheckIsTextEditor() { return true; }
            @Override public InputConnection onCreateInputConnection(EditorInfo info) {
                final int epoch = keyboardEpoch;
                info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
                info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_NONE;
                info.initialSelStart = info.initialSelEnd = 0;
                return new BaseInputConnection(this, false) {
                    @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                        if (!acceptsKeyboard(epoch)) return true; composition = ""; if (text != null) listener.onTextInput(text.toString()); return true;
                    }
                    @Override public boolean setComposingText(CharSequence text, int position) {
                        if (!acceptsKeyboard(epoch)) return true;
                        if (text != null && text.length() > 8192) { composition = ""; listener.onTextInput(text.toString()); return true; }
                        composition = text == null ? "" : text.toString(); return true;
                    }
                    @Override public boolean finishComposingText() {
                        if (acceptsKeyboard(epoch) && !composition.isEmpty()) { String c = composition; composition = ""; listener.onTextInput(c); } return true;
                    }
                    @Override public boolean deleteSurroundingText(int before, int after) {
                        if (!acceptsKeyboard(epoch)) return true;
                        if (!composition.isEmpty()) { composition = ""; return true; }
                        for (int i = 0; i < Math.min(128, Math.max(0, before)); i++) tap(0xff08);
                        for (int i = 0; i < Math.min(128, Math.max(0, after)); i++) tap(0xffff);
                        return true;
                    }
                    @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) { return deleteSurroundingText(before, after); }
                    @Override public CharSequence getTextBeforeCursor(int n, int flags) { return ""; }
                    @Override public CharSequence getTextAfterCursor(int n, int flags) { return ""; }
                    @Override public CharSequence getSelectedText(int flags) { return ""; }
                    @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
                        ExtractedText t = new ExtractedText(); t.text = ""; return t;
                    }
                    @Override public boolean sendKeyEvent(KeyEvent event) { return !acceptsKeyboard(epoch) || handleKey(event); }
                    @Override public boolean performEditorAction(int actionCode) { if (acceptsKeyboard(epoch)) tap(0xff0d); return true; }
                    @Override public void closeConnection() { if (epoch == keyboardEpoch) composition = ""; super.closeConnection(); }
                };
            }
        };
        keyboard.setBackgroundColor(Color.TRANSPARENT);
        keyboard.setFocusable(true); keyboard.setFocusableInTouchMode(true);
        keyboard.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        keyboard.setContentDescription("Remote keyboard. Text is sent to the selected PC.");
        root.addView(keyboard, new FrameLayout.LayoutParams(1, 1));
        listener.onInputPolicy(options);
    }
    boolean releaseHeldKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_UP) return false;
        Integer keysym = heldHardware.release(event.getKeyCode());
        if (keysym == null) return false;
        listener.onKeyInput(keysym, false); return true;
    }
    boolean handleKey(KeyEvent event) {
        if (releaseHeldKey(event)) return true;
        if (!acceptsInput() || !options.keyboard || event.getEventTime() <= rearmAtMs || event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_HOME
                || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                || event.getKeyCode() == KeyEvent.KEYCODE_POWER) return false;
        int code = event.getKeyCode();
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (event.getRepeatCount() > 0 && !heldHardware.contains(code)) return true;
        int keysym = Keysyms.special(code);
        if (keysym == 0) {
            int cp = event.getUnicodeChar((event.getMetaState() & (KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON)) != 0 ? 0 : event.getMetaState());
            if (cp <= 0 || (cp & android.view.KeyCharacterMap.COMBINING_ACCENT) != 0) return false;
            keysym = Keysyms.fromCodePoint(cp);
        }
        if (heldHardware.press(code, keysym)) listener.onKeyInput(keysym, true);
        return true;
    }
    private boolean acceptsInput() { return !released && lifecycle.allowsInput() && activity.hasWindowFocus(); }
    private boolean acceptsKeyboard(int epoch) { return epoch == keyboardEpoch && options.keyboard && acceptsInput(); }
    void setResumed(boolean resumed) { lifecycle.resumed(resumed); if (!resumed) lostFocus(); }
    void setWindowFocused(boolean focused) {
        lifecycle.focused(focused); if (!focused) lostFocus();
        else if (keyboard.hasFocus() && options.keyboard) restartKeyboard();
    }
    void policyChanged() {
        keyboardEpoch++; rearmAtMs = SystemClock.uptimeMillis(); composition = ""; heldHardware.clear();
        pointerId = -1; pointerOwnership.reset();
        options = settings.inputOptions(); pointerMode = options.pointerMode;
        allowedDevices = pointerMode == PointerPolicy.EXCEPT_DEVICES ? settings.excludedDevices() : settings.devices();
        listener.onInputPolicy(options);
        if (options.keyboard && keyboard.hasFocus()) restartKeyboard();
        else if (!options.keyboard) {
            InputMethodManager ime = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(keyboard.getWindowToken(), 0);
        }
    }
    void lostFocus() {
        keyboardEpoch++; rearmAtMs = SystemClock.uptimeMillis(); composition = ""; heldHardware.clear();
        pointerId = -1; pointerOwnership.reset(); listener.releaseAll();
    }
    private void restartKeyboard() {
        InputMethodManager ime = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (ime != null) ime.restartInput(keyboard);
    }
    void showKeyboard() {
        if (!options.keyboard) return;
        keyboard.requestFocus(); restartKeyboard();
        keyboard.post(() -> { InputMethodManager ime = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (acceptsInput() && options.keyboard && ime != null) ime.showSoftInput(keyboard, InputMethodManager.SHOW_IMPLICIT); });
    }
    void release() {
        lostFocus(); released = true;
        if (inputManager != null) inputManager.unregisterInputDeviceListener(deviceListener);
        surface.setOnTouchListener(null); surface.setOnGenericMotionListener(null);
        InputMethodManager ime = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(keyboard.getWindowToken(), 0);
    }
    private void tap(int keysym) { if (options.keyboard && acceptsInput()) { listener.onKeyInput(keysym, true); listener.onKeyInput(keysym, false); } }
    private boolean allowsDevice(InputDevice device) {
        return options.pointerActive() && PointerPolicy.permits(pointerMode, device != null && !device.isVirtual(),
                device == null ? null : device.getDescriptor(), allowedDevices);
    }
    private void releaseOwnedPointer() {
        pointerOwnership.reset(); pointerId = -1; listener.releasePointer();
    }
    private boolean capturePointer(MotionEvent event) {
        if (!acceptsInput() || event.getEventTime() <= rearmAtMs) return true;
        int count = event.getPointerCount(), action = event.getActionMasked();
        ConnectionCoordinator.INSTANCE.diagnostics.record(SystemClock.elapsedRealtime(), event.getSource(), count > 0 ? event.getToolType(0) : 0,
                event.getDeviceId(), action, event.getButtonState());
        InputDevice device = event.getDevice();
        String descriptor = device == null ? null : device.getDescriptor();
        int decision = pointerOwnership.gate(event.getDeviceId(), descriptor, allowsDevice(device), action, event.getButtonState());
        if (decision == PointerOwnership.IGNORE) return true;
        if (decision == PointerOwnership.RELEASE_OWNER) { releaseOwnedPointer(); return true; }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_HOVER_EXIT) {
            releaseOwnedPointer(); return true;
        }
        if (count == 0 || surface.getWidth() <= 0 || surface.getHeight() <= 0) return true;
        if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) == 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN) return false;
        if (action == MotionEvent.ACTION_DOWN) pointerId = event.getPointerId(0);
        if (action == MotionEvent.ACTION_POINTER_DOWN) return true;
        if (action == MotionEvent.ACTION_POINTER_UP) {
            if (event.getPointerId(event.getActionIndex()) == pointerId) releaseOwnedPointer();
            return true;
        }
        int index = pointerId < 0 ? 0 : event.findPointerIndex(pointerId);
        if (index < 0) { releaseOwnedPointer(); return true; }
        float x = event.getX(index) / surface.getWidth(), y = event.getY(index) / surface.getHeight();
        if (!Float.isFinite(x) || !Float.isFinite(y)) { releaseOwnedPointer(); return true; }
        pointerOwnership.forwarded(event.getDeviceId(), descriptor, action, event.getButtonState());
        listener.onPointerInput(new PointerEvent(action, x, y, event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getButtonState()));
        if (action == MotionEvent.ACTION_UP) pointerId = -1;
        return true;
    }
}
