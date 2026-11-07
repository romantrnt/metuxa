package com.metashare.client;

/** Fail-closed focus lifecycle independent of Android callback ordering. */
final class InputLifecycle {
    private boolean resumed, focused;
    void resumed(boolean value) { resumed = value; }
    void focused(boolean value) { focused = value; }
    boolean allowsInput() { return resumed && focused; }
}
