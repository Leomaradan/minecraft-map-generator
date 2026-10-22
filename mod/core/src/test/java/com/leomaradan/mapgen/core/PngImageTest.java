package com.leomaradan.mapgen.core;

import com.leomaradan.mapgen.core.png.PngImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PngImageTest {

    private static PngImage decode(byte[] png) throws IOException {
        return PngImage.read(new ByteArrayInputStream(png));
    }

    @Test
    void decodes8BitGrayWithEveryFilter() throws IOException {
        int w = 37;
        int h = 23;
        PngImage image = decode(TestPng.gray(w, h, 8, (x, y, c) -> (x * 7 + y * 13) & 0xFF));
        assertEquals(8, image.bitDepth());
        assertTrue(image.isGrayscale());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                assertEquals((x * 7 + y * 13) & 0xFF, image.sample(x, y, 0));
            }
        }
    }

    @Test
    void decodes16BitGray() throws IOException {
        int w = 19;
        int h = 11;
        PngImage image = decode(TestPng.gray(w, h, 16, (x, y, c) -> (x * 3001 + y * 977) & 0xFFFF));
        assertEquals(16, image.bitDepth());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                assertEquals((x * 3001 + y * 977) & 0xFFFF, image.sample(x, y, 0));
            }
        }
    }

    @Test
    void decodesSubByteGray() throws IOException {
        PngImage image = decode(TestPng.gray(13, 5, 2, (x, y, c) -> (x + y) % 4));
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 13; x++) {
                assertEquals((x + y) % 4, image.sample(x, y, 0));
                int v = ((x + y) % 4) * 255 / 3;
                assertEquals((v << 16) | (v << 8) | v, image.rgb(x, y));
            }
        }
    }

    @Test
    void decodesInterlacedImages() throws IOException {
        int w = 21;
        int h = 17;
        PngImage gray16 = decode(TestPng.encode(w, h, 16, 0, 1, (x, y, c) -> x * 1000 + y, null, true));
        PngImage gray1 = decode(TestPng.encode(w, h, 1, 0, 1, (x, y, c) -> (x ^ y) & 1, null, true));
        PngImage rgb = decode(TestPng.encode(w, h, 8, 2, 3, (x, y, c) -> (x * 10 + y + c * 50) & 0xFF, null, true));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                assertEquals(x * 1000 + y, gray16.sample(x, y, 0));
                assertEquals((x ^ y) & 1, gray1.sample(x, y, 0));
                for (int c = 0; c < 3; c++) {
                    assertEquals((x * 10 + y + c * 50) & 0xFF, rgb.sample(x, y, c));
                }
            }
        }
    }

    @Test
    void decodesPalette() throws IOException {
        int[] palette = {0x112233, 0xFF0000, 0x00FF00, 0x0000FF};
        PngImage image = decode(TestPng.encode(9, 9, 2, 3, 1, (x, y, c) -> (x + 2 * y) % 4, palette, false));
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                assertEquals(palette[(x + 2 * y) % 4], image.rgb(x, y));
            }
        }
    }

    @Test
    void matchesImageIoOnRgbImages() throws IOException {
        BufferedImage source = new BufferedImage(31, 29, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 29; y++) {
            for (int x = 0; x < 31; x++) {
                source.setRGB(x, y, (x * 8) << 16 | (y * 8) << 8 | ((x + y) * 4));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);
        PngImage image = decode(out.toByteArray());
        for (int y = 0; y < 29; y++) {
            for (int x = 0; x < 31; x++) {
                assertEquals(source.getRGB(x, y) & 0xFFFFFF, image.rgb(x, y));
            }
        }
    }

    @Test
    void matchesImageIoOn16BitGray() throws IOException {
        BufferedImage source = new BufferedImage(16, 16, BufferedImage.TYPE_USHORT_GRAY);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                source.getRaster().setSample(x, y, 0, x * 4096 + y * 7);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);
        PngImage image = decode(out.toByteArray());
        assertEquals(16, image.bitDepth());
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(x * 4096 + y * 7, image.sample(x, y, 0));
            }
        }
    }

    @Test
    void rejectsNonPng() {
        assertThrows(IOException.class, () -> decode("definitely not a png".getBytes()));
    }

    @Test
    void rejectsTruncatedPng() {
        byte[] png = TestPng.gray(8, 8, 8, (x, y, c) -> x);
        byte[] truncated = java.util.Arrays.copyOf(png, png.length - 30);
        assertThrows(IOException.class, () -> decode(truncated));
    }
}
