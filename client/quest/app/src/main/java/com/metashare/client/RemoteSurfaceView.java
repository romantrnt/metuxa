package com.metashare.client;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.TextureView;

/** GPU-composited video follows the same View transform used for inverse input dispatch. */
final class RemoteSurfaceView extends TextureView implements TextureView.SurfaceTextureListener {
    interface Listener {
        void renderSurfaceAvailable();
        void renderSurfaceSizeChanged();
        void renderSurfaceDestroyed();
    }
    private volatile RenderSurface target;
    private Listener listener;
    RemoteSurfaceView(Context context) { super(context); setOpaque(true); setSurfaceTextureListener(this); }
    void setRenderListener(Listener listener) { this.listener = listener; }
    RenderSurface.Lease acquireSurface() { RenderSurface current = target; return current == null ? null : current.acquire(); }
    void setMonitorRotation(int degrees) {
        // TextureView composites the decoded buffer into this View, unlike a
        // separately composed SurfaceView layer. Android inversely transforms
        // touch/hover into the original video coordinates before input dispatch.
        setRotation(MonitorGeometry.rotation(degrees));
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        RenderSurface previous = target;
        if (previous != null && previous.texture == texture) return;
        target = new RenderSurface(texture);
        if (previous != null) previous.retire();
        if (listener != null) listener.renderSurfaceAvailable();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        if (listener != null) listener.renderSurfaceSizeChanged();
    }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        RenderSurface previous = target;
        if (previous == null || previous.texture != texture) return false;
        target = null;
        try { if (listener != null) listener.renderSurfaceDestroyed(); }
        finally { previous.retire(); }
        // The final decoder lease releases Surface and SurfaceTexture after codec
        // cleanup. Returning true would let TextureView release them too early.
        return false;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
    @Override public boolean performClick() { super.performClick(); return true; }
}
