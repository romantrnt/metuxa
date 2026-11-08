package com.metashare.client;

/** Clock-independent overlay policy. A revealed panel can always be pinned with the handle. */
final class PanelVisibility {
    static final int ALWAYS = 0, HOVER = 1, AUTO_HIDE = 2;
    private int mode = AUTO_HIDE;
    private boolean pinned, hovering, interacting, focused, resumed = true;
    private long visibleUntil;
    void mode(int value) { mode = value >= ALWAYS && value <= AUTO_HIDE ? value : AUTO_HIDE; }
    void reveal(long now, long delay) { visibleUntil = now + Math.max(0, delay); }
    void pin(boolean value) { pinned = value; }
    void hovering(boolean value) { hovering = value; }
    void interacting(boolean value) { interacting = value; }
    void focused(boolean value) { focused = value; }
    void resumed(boolean value) { resumed = value; }
    boolean visible(long now) { return mode == ALWAYS || pinned || resumed && (hovering || interacting || focused || mode == AUTO_HIDE && now < visibleUntil); }
    boolean timed(long now) { return resumed && mode == AUTO_HIDE && !pinned && !hovering && !interacting && !focused && now < visibleUntil; }
}
