package com.metashare.client;

/** Rotation uses Android's clockwise view transform; dimensions stay in video coordinates. */
final class MonitorGeometry {
    static int rotation(int value) { return value == 90 || value == 180 || value == 270 ? value : 0; }
    static int[] fit(int viewportWidth, int viewportHeight, int videoWidth, int videoHeight, int rotation) {
        if (viewportWidth <= 0 || viewportHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return new int[] {0, 0};
        boolean sideways = rotation(rotation) % 180 != 0;
        double scale = Math.min((double) viewportWidth / (sideways ? videoHeight : videoWidth),
                (double) viewportHeight / (sideways ? videoWidth : videoHeight));
        return new int[] {Math.max(1, (int) Math.floor(videoWidth * scale)), Math.max(1, (int) Math.floor(videoHeight * scale))};
    }
    /** Screen fractions to video fractions. Android applies this inverse during View dispatch. */
    static float[] inverse(float x, float y, int rotation) {
        switch (rotation(rotation)) {
            case 90: return new float[] {y, 1 - x};
            case 180: return new float[] {1 - x, 1 - y};
            case 270: return new float[] {1 - y, x};
            default: return new float[] {x, y};
        }
    }
    /** Initial panel size in dp, with a bounded long edge and the stream aspect. */
    static int[] panelSize(int width, int height) {
        if (width < 16 || height < 16 || width > 8192 || height > 8192) return new int[] {1280, 720};
        double scale = 1280d / Math.max(width, height);
        return new int[] {Math.max(360, (int) Math.round(width * scale)), Math.max(225, (int) Math.round(height * scale))};
    }
    private MonitorGeometry() {}
}
