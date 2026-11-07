package com.metashare.client;

import android.os.Handler;
import android.os.Looper;
import java.util.function.Consumer;

/** The only bridge from local input into a live, selected connection. */
final class SessionInputForwarder implements RemoteInputController.Listener {
    private StreamSession session;
    private InputOptions options = InputOptions.full();
    private boolean connected, inputCapable, draining, announcingText;
    private Consumer<String> status = message -> {};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final KeyboardQueue keyboard = new KeyboardQueue();
    private final Runnable pumpTask = this::pump;
    private final InputState state = new InputState(new InputState.Sink() {
        public void move(float x, float y) { if (ready()) session.sendPointerMove(x, y); }
        public void button(int b, boolean down) { if (ready()) session.sendPointerButton(b, down); }
        public void scroll(int v, int h) { if (ready()) session.sendScroll(v, h); }
        public void key(int k, boolean down) { if (ready()) session.sendKey(k, down); }
    });
    private final KeyboardQueue.Sink keyboardSink = new KeyboardQueue.Sink() {
        public void character(int codePoint) { state.tap(Keysyms.fromCodePoint(codePoint)); }
        public void key(int keysym, boolean down) { state.key(keysym, down); }
    };
    void setStatusListener(Consumer<String> listener) { status = listener; }
    void setSession(StreamSession next) { releaseAll(); session = next; connected = false; inputCapable = false; state.clearDisconnected(); }
    void connected() { state.clearDisconnected(); connected = true; }
    void disconnected() { cancelKeyboard(); connected = false; inputCapable = false; state.clearDisconnected(); }
    void inputCapability(boolean enabled) { if (!enabled) releaseAll(); inputCapable = enabled; }
    public void onInputPolicy(InputOptions next) {
        cancelKeyboard();
        state.configure(next);
        if (ready()) session.releaseAllInput(); // discard every queued event from the old policy
        options = next;
    }
    private boolean ready() { return session != null && connected && inputCapable; }
    public void onPointerInput(RemoteInputController.PointerEvent e) {
        if (ready()) state.pointer(e.action, e.x, e.y, e.verticalScroll, e.horizontalScroll, e.buttons);
    }
    public void onTextInput(String text) {
        if (!ready() || !options.keyboard || text == null) return;
        if (!keyboard.text(text)) { status.accept("Text was not sent: at most 4096 pending characters. Wait for the current text or paste a smaller piece."); return; }
        if (text.length() > 32) { announcingText = true; status.accept("Sending pasted text… Keep this window focused until it finishes."); }
        if (!draining) pump();
    }
    public void onKeyInput(int keysym, boolean down) {
        if (!ready() || !options.keyboard) return;
        if (!draining && keyboard.size() == 0) { state.key(keysym, down); return; }
        if (!keyboard.key(keysym, down)) { releaseAll(); status.accept("Keyboard queue full. Pending input cancelled; please try again."); }
    }
    private void pump() {
        if (!ready() || !options.keyboard) { cancelKeyboard(); return; }
        draining = true;
        // 160 characters/sec => at most 320 key transitions/sec, below the host
        // flood limit and leaving headroom for pointer and normal physical keys.
        keyboard.drain(4, keyboardSink);
        if (keyboard.size() > 0) main.postDelayed(pumpTask, 25);
        else { draining = false; if (announcingText) { announcingText = false; status.accept("Text sent."); } }
    }
    private void cancelKeyboard() {
        main.removeCallbacks(pumpTask);
        if (keyboard.size() > 0 && announcingText) status.accept("Pending text cancelled because input settings changed, focus was lost or the session disconnected. Some text may already have arrived.");
        keyboard.clear(); draining = announcingText = false;
    }
    public void releasePointer() { state.releasePointer(); }
    public void releaseAll() { cancelKeyboard(); state.releaseAll(); if (ready()) session.releaseAllInput(); }
}
