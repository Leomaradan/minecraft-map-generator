package com.leomaradan.mapgen.core;

/** Distance in pixels to the nearest seed pixel, by a two-pass chamfer distance transform. */
final class DistanceField {
    /** Largest stored distance, in pixels. */
    static final int MAX = 255;
    /** Chamfer distance weights (orthogonal, diagonal), roughly 1 and sqrt(2) times 3. */
    private static final int ORTHOGONAL = 3;
    private static final int DIAGONAL = 4;
    /** Initial value of the pixels that are not seeds. */
    static final short FAR = (short) (MAX * ORTHOGONAL);

    private DistanceField() {
    }

    /**
     * @param d seeds at 0, other pixels at {@link #FAR}; overwritten
     * @return distance of each pixel in pixels, capped at {@link #MAX} (read with {@code & 0xFF})
     */
    static byte[] compute(short[] d, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = d[y * w + x];
                if (x > 0) {
                    v = Math.min(v, d[y * w + x - 1] + ORTHOGONAL);
                }
                if (y > 0) {
                    v = Math.min(v, d[(y - 1) * w + x] + ORTHOGONAL);
                    if (x > 0) {
                        v = Math.min(v, d[(y - 1) * w + x - 1] + DIAGONAL);
                    }
                    if (x + 1 < w) {
                        v = Math.min(v, d[(y - 1) * w + x + 1] + DIAGONAL);
                    }
                }
                d[y * w + x] = (short) v;
            }
        }
        byte[] result = new byte[w * h];
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                int v = d[y * w + x];
                if (x + 1 < w) {
                    v = Math.min(v, d[y * w + x + 1] + ORTHOGONAL);
                }
                if (y + 1 < h) {
                    v = Math.min(v, d[(y + 1) * w + x] + ORTHOGONAL);
                    if (x + 1 < w) {
                        v = Math.min(v, d[(y + 1) * w + x + 1] + DIAGONAL);
                    }
                    if (x > 0) {
                        v = Math.min(v, d[(y + 1) * w + x - 1] + DIAGONAL);
                    }
                }
                d[y * w + x] = (short) v;
                result[y * w + x] = (byte) Math.min(MAX, v / ORTHOGONAL);
            }
        }
        return result;
    }
}
