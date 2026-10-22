package com.leomaradan.mapgen.core;

import com.leomaradan.mapgen.core.png.PngImage;

/**
 * Single-value view of a PNG used as a heightmap, water map or lava map. Grayscale images are read
 * directly; for color images the red channel is used.
 */
public final class GrayRaster {
    private final PngImage image;
    private final int bitDepth;

    private GrayRaster(PngImage image, int bitDepth) {
        this.image = image;
        this.bitDepth = bitDepth;
    }

    /**
     * Wraps an image, or reports why it can't be used as a height raster.
     *
     * @return null if the image is not usable
     */
    public static GrayRaster of(PngImage image, String fileName, ValidationReport report) {
        int depth = effectiveBitDepth(image);
        if (depth != 8 && depth != 16) {
            report.error(fileName + ": must be an 8-bit or 16-bit image (found " + image.bitDepth() + "-bit)");
            return null;
        }
        if (!image.isGrayscale()) {
            report.warn(fileName + ": is not a grayscale image, its red channel is used");
        }
        return new GrayRaster(image, depth);
    }

    /** 8 or 16 for usable images. Palette images are 8-bit (the palette holds 8-bit colors). */
    public static int effectiveBitDepth(PngImage image) {
        return image.colorType() == PngImage.COLOR_PALETTE ? 8 : image.bitDepth();
    }

    public int width() {
        return this.image.width();
    }

    public int height() {
        return this.image.height();
    }

    public int bitDepth() {
        return this.bitDepth;
    }

    public int value(int x, int y) {
        if (this.image.colorType() == PngImage.COLOR_PALETTE) {
            return (this.image.rgb(x, y) >> 16) & 0xFF;
        }
        return this.image.sample(x, y, 0);
    }
}
