package com.metashare.client;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;

/** Bars cover the video only while visible. The explicit handle never sends a PC click. */
final class PanelOverlay extends FrameLayout {
    private final PanelVisibility topState = new PanelVisibility(), bottomState = new PanelVisibility();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AppSettings settings;
    private final Runnable tick = this::render;
    private final Runnable localInput;
    private View top, bottom;
    private Button handle;
    private boolean pinned, resumed = true, closed;
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = this::focusChanged;
    private void focusChanged(View old, View next) {
        if (old != next && (inside(top, next) || inside(bottom, next) || next == handle)) localInput.run();
        topState.focused(inside(top, next)); bottomState.focused(inside(bottom, next)); render();
    }
    PanelOverlay(Context context, Runnable localInput) { super(context); settings = new AppSettings(context); this.localInput = localInput; }
    void panels(View top, View bottom) {
        this.top = top; this.bottom = bottom;
        handle = QuestUi.button(getContext(), "Controls", false);
        handle.setContentDescription("Show monitor controls. This button does not control your PC.");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        params.setMargins(QuestUi.dp(getContext(), 4), QuestUi.dp(getContext(), 4), QuestUi.dp(getContext(), 4), 0);
        addView(handle, params);
        handle.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (top instanceof android.widget.TextView) top.setPaddingRelative(top.getPaddingStart(), top.getPaddingTop(), r - l + QuestUi.dp(getContext(), 8), top.getPaddingBottom());
        });
        handle.setOnClickListener(v -> { localInput.run(); pinned = !pinned; topState.pin(pinned); bottomState.pin(pinned);
            handle.setText(pinned ? "Hide controls" : "Controls"); handle.setContentDescription(pinned ? "Hide controls, except bars set to always visible" : "Show monitor controls"); handle.setSelected(pinned);
            if (!pinned) { topState.reveal(0, 0); bottomState.reveal(0, 0); clearControlFocus(); } render(); });
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);
        configure(); reveal();
    }
    private void clearControlFocus() { View focused = findFocus(); if (inside(top, focused) || inside(bottom, focused)) { focused.clearFocus(); requestFocus(); } }
    void configure() {
        topState.mode(settings.topBar()); bottomState.mode(settings.bottomBar());
        int alpha = settings.panelOpacity() * 255 / 100;
        int panel = QuestUi.color(getContext(), R.color.quest_panel) & 0xffffff | alpha << 24;
        if (top != null) top.setBackgroundColor(panel);
        if (bottom != null) bottom.setBackgroundColor(panel);
        render();
    }
    void reveal() { long now = SystemClock.uptimeMillis(); topState.reveal(now, settings.hideDelay() * 1000L); bottomState.reveal(now, settings.hideDelay() * 1000L); render(); }
    void resumed(boolean value) {
        resumed = value; topState.resumed(value); bottomState.resumed(value);
        topState.hovering(false); bottomState.hovering(false); topState.interacting(false); bottomState.interacting(false);
        if (value) reveal(); else handler.removeCallbacks(tick);
    }
    void close() { closed = true; handler.removeCallbacks(tick); getViewTreeObserver().removeOnGlobalFocusChangeListener(focusListener); }
    private void render() {
        handler.removeCallbacks(tick); if (closed || top == null || bottom == null) return;
        long now = SystemClock.uptimeMillis();
        top.setVisibility(topState.visible(now) ? VISIBLE : INVISIBLE);
        bottom.setVisibility(bottomState.visible(now) ? VISIBLE : INVISIBLE);
        if (resumed && (topState.timed(now) || bottomState.timed(now))) handler.postDelayed(tick, 200);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            boolean onTop = hit(top, event), onBottom = hit(bottom, event);
            topState.interacting(onTop); bottomState.interacting(onBottom);
            if (onTop || onBottom || hit(handle, event)) localInput.run();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            topState.interacting(false); bottomState.interacting(false);
            if (hit(top, event) || hit(bottom, event)) reveal();
        }
        render(); return super.dispatchTouchEvent(event);
    }
    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        boolean leaving = event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT;
        float edge = QuestUi.dp(getContext(), 16);
        // Hover is a convenience. Touch/controllers always have the visible Controls handle.
        boolean nearTop = !leaving && (hit(top, event) || event.getY() < edge || hit(handle, event));
        boolean nearBottom = !leaving && (hit(bottom, event) || event.getY() > getHeight() - edge);
        topState.hovering(nearTop); bottomState.hovering(nearBottom);
        if (nearTop) topState.reveal(SystemClock.uptimeMillis(), settings.hideDelay() * 1000L);
        if (nearBottom) bottomState.reveal(SystemClock.uptimeMillis(), settings.hideDelay() * 1000L);
        render(); return super.dispatchHoverEvent(event);
    }
    private boolean hit(View view, MotionEvent event) {
        return view != null && view.getVisibility() == VISIBLE && event.getX() >= view.getLeft() && event.getX() < view.getRight()
            && event.getY() >= view.getTop() && event.getY() < view.getBottom();
    }
    private static boolean inside(View ancestor, View view) {
        if (ancestor == null || view == null) return false;
        while (view != null) { if (view == ancestor) return true; view = view.getParent() instanceof View ? (View) view.getParent() : null; }
        return false;
    }
}
