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
    private MonitorGeometry() {}
}
