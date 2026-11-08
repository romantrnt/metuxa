package com.metashare.client;

import android.graphics.SurfaceTexture;
import android.view.Surface;

/** TextureView and the decoder each retain the producer until they have finished with it. */
final class RenderSurface {
    final SurfaceTexture texture;
    private final Surface surface;
    private final SurfaceLifetime lifetime;
    RenderSurface(SurfaceTexture texture) {
        this.texture = texture; surface = new Surface(texture);
        lifetime = new SurfaceLifetime(() -> { try { surface.release(); } finally { texture.release(); } });
    }
    Lease acquire() { return lifetime.retain() ? new Lease(surface, lifetime) : null; }
    void retire() { lifetime.retire(); }
    static final class Lease implements AutoCloseable {
        private final Surface surface;
        private SurfaceLifetime lifetime;
        Lease(Surface surface, SurfaceLifetime lifetime) { this.surface = surface; this.lifetime = lifetime; }
        Surface surface() { return surface; }
        @Override public void close() {
            SurfaceLifetime previous;
            synchronized (this) { previous = lifetime; lifetime = null; }
            if (previous != null) previous.release();
        }
    }
}
