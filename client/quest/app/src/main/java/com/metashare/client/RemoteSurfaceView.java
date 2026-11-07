package com.metashare.client;

import android.content.Context;
import android.view.SurfaceView;

/** Semantic click announces the remote viewport without synthesizing an unlocated PC click. */
final class RemoteSurfaceView extends SurfaceView {
    RemoteSurfaceView(Context context) { super(context); }
    @Override public boolean performClick() { super.performClick(); return true; }
}
