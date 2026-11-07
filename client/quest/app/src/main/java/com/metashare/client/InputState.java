package com.metashare.client;

import java.util.HashSet;
import java.util.Set;

/** Android-independent, testable held-input ledger. All calls are on the UI thread. */
final class InputState {
    interface Sink {
        void move(float x, float y);
        void button(int code, boolean down);
        void scroll(int vertical, int horizontal);
        void key(int keysym, boolean down);
    }
    static final int PRIMARY = 1, SECONDARY = 2, TERTIARY = 4;
    private final Sink sink;
    private final Set<Integer> keys = new HashSet<>();
    private int buttons;
    private InputOptions options = InputOptions.full();
    private boolean contact;
    private float vertical, horizontal;
    InputState(Sink sink) { this.sink = sink; }
    void configure(InputOptions next) {
        // Release under the old policy, then replace it; release events are never gated.
        releaseAll(); options = next;
    }
    void pointer(int action, float x, float y, float v, float h, int reportedButtons) {
        // Android constants are stable: DOWN=0 UP=1 MOVE=2 CANCEL=3 HOVER_MOVE=7
        // SCROLL=8 HOVER_ENTER=9 HOVER_EXIT=10 BUTTON_PRESS=11 BUTTON_RELEASE=12.
        if (!options.pointerActive()) { releasePointer(); return; }
        if (action == 3 || action == 10) { releasePointer(); return; }
        if (!Float.isFinite(x) || !Float.isFinite(y)) { releasePointer(); return; }
        if (options.click && action == 0 && reportedButtons == 0) contact = true;
        if (action == 1) contact = false;
        if (options.move && (action == 0 || action == 1 || action == 2 || action == 7 || action == 9 || action == 11 || action == 12)) {
            if (Float.isFinite(x) && Float.isFinite(y)) sink.move(clamp(x), clamp(y));
        }
        // ACTION_UP terminates contact even if Android's final event contains stale bits.
        int next = !options.click || action == 1 ? 0 : (reportedButtons & 7) | (contact ? PRIMARY : 0);
        transition(next);
        if (options.scroll && action == 8 && Float.isFinite(v) && Float.isFinite(h)) {
            vertical += Math.max(-100, Math.min(100, v));
            horizontal += Math.max(-100, Math.min(100, h));
            int vs = (int) vertical, hs = (int) horizontal;
            if (vs != 0 || hs != 0) { sink.scroll(-vs, -hs); vertical -= vs; horizontal -= hs; }
        }
    }
    void key(int keysym, boolean down) {
        if (!options.keyboard || keysym <= 0) return;
        if (down) {
            if (keys.add(keysym)) sink.key(keysym, true);
        } else if (keys.remove(keysym)) sink.key(keysym, false);
    }
    void tap(int keysym) {
        // Do not release a physically held key when IME commits the same keysym.
        if (!options.keyboard || keys.contains(keysym)) return;
        sink.key(keysym, true); sink.key(keysym, false);
    }
    void releasePointer() { contact = false; transition(0); vertical = horizontal = 0; }
    void releaseAll() {
        releasePointer();
        for (int k : keys) sink.key(k, false);
        keys.clear();
    }
    void clearDisconnected() { buttons = 0; contact = false; vertical = horizontal = 0; keys.clear(); }
    private void transition(int next) {
        int changed = next ^ buttons;
        if ((changed & PRIMARY) != 0) sink.button(272, (next & PRIMARY) != 0);
        if ((changed & SECONDARY) != 0) sink.button(273, (next & SECONDARY) != 0);
        if ((changed & TERTIARY) != 0) sink.button(274, (next & TERTIARY) != 0);
        buttons = next;
    }
    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
}
