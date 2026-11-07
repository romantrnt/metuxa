package com.metashare.client;

/** Immutable action policy shared by persistence, capture, forwarding and JVM tests. */
final class InputOptions {
    static final String MOVE = "input_move", CLICK = "input_click", SCROLL = "input_scroll", KEYBOARD = "input_keyboard";
    static final int FULL_REMOTE = 0, KEYBOARD_ONLY = 1, VIEW_ONLY = 2, CUSTOM = 3;
    interface Flags { boolean get(String key, boolean fallback); }
    final int pointerMode;
    final boolean move, click, scroll, keyboard;
    InputOptions(int mode, boolean move, boolean click, boolean scroll, boolean keyboard) {
        pointerMode = mode; this.move = move; this.click = click; this.scroll = scroll; this.keyboard = keyboard;
    }
    static InputOptions read(int mode, Flags flags) {
        return new InputOptions(mode, flags.get(MOVE, true), flags.get(CLICK, true), flags.get(SCROLL, true), flags.get(KEYBOARD, true));
    }
    static InputOptions full() { return new InputOptions(PointerPolicy.FULL, true, true, true, true); }
    static InputOptions preset(int preset) {
        if (preset == FULL_REMOTE) return full();
        if (preset == KEYBOARD_ONLY) return new InputOptions(PointerPolicy.FULL, false, false, false, true);
        if (preset == VIEW_ONLY) return new InputOptions(PointerPolicy.FULL, false, false, false, false);
        throw new IllegalArgumentException("Custom is derived from the actual controls, not a preset.");
    }
    boolean pointerActions() { return move || click || scroll; }
    boolean pointerActive() {
        return pointerActions() && (pointerMode == PointerPolicy.FULL || pointerMode == PointerPolicy.DEVICES || pointerMode == PointerPolicy.EXCEPT_DEVICES);
    }
    int preset() {
        if (pointerMode == PointerPolicy.FULL && move && click && scroll && keyboard) return FULL_REMOTE;
        if (!pointerActive()) return keyboard ? KEYBOARD_ONLY : VIEW_ONLY;
        return CUSTOM;
    }
    String presetLabel() {
        switch (preset()) {
            case FULL_REMOTE: return "Full remote";
            case KEYBOARD_ONLY: return "Keyboard only";
            case VIEW_ONLY: return "View only";
            default: return "Custom";
        }
    }
}
