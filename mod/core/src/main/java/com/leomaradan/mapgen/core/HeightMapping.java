package com.leomaradan.mapgen.core;

/**
 * Linear mapping from a raster value to a world Y: {@code y = baseY + value * scale}. Shared by the
 * heightmap and the water and lava maps, which all use the same bit depth.
 */
public record HeightMapping(double baseY, double scale, int bitDepth) {

    public static HeightMapping resolve(MapConfig.HeightSpec spec, int bitDepth) {
        int maxValue = maxValue(bitDepth);
        if (spec.isRange()) {
            return new HeightMapping(spec.minY(), (spec.maxY() - spec.minY()) / maxValue, bitDepth);
        }
        if (spec.baseY() != null) {
            return new HeightMapping(spec.baseY(), spec.scale(), bitDepth);
        }
        // Defaults: 8-bit maps one value to one block from Y=0; 16-bit maps the full range onto
        // the same 0..255 span with sub-block precision, so both bit depths look the same.
        return bitDepth == 16 ? new HeightMapping(0, 255.0 / maxValue, bitDepth) : new HeightMapping(0, 1, bitDepth);
    }

    public static int maxValue(int bitDepth) {
        return (1 << bitDepth) - 1;
    }

    /** Exact (fractional) Y of a raster value. */
    public double toY(double value) {
        return this.baseY + value * this.scale;
    }

    /** Y of the top solid block for a raster value. */
    public int toBlockY(double value) {
        return (int) Math.floor(this.toY(value));
    }

    public double minY() {
        return this.toY(0);
    }

    public double maxY() {
        return this.toY(maxValue(this.bitDepth));
    }
}
