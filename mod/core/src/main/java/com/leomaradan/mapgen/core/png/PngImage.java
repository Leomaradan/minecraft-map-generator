package com.leomaradan.mapgen.core.png;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Minimal, dependency-free PNG decoder giving raw access to samples.
 * <p>
 * Unlike {@code javax.imageio}, it keeps the exact bit depth of the file (needed to tell 8-bit from
 * 16-bit heightmaps) and does not depend on the {@code java.desktop} module. Supports every color
 * type, bit depths 1 to 16 and Adam7 interlacing. Ancillary chunks (gamma, ICC...) are ignored.
 * <p>
 * Instances are immutable and safe to share between threads.
 */
public final class PngImage {
    public static final int COLOR_GRAY = 0;
    public static final int COLOR_RGB = 2;
    public static final int COLOR_PALETTE = 3;
    public static final int COLOR_GRAY_ALPHA = 4;
    public static final int COLOR_RGBA = 6;

    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    /** Refuses images whose decoded size would not fit in a Java array. */
    private static final long MAX_DECODED_BYTES = Integer.MAX_VALUE - 64;

    private final int width;
    private final int height;
    private final int bitDepth;
    private final int colorType;
    private final int channels;
    /** Unfiltered, de-interlaced samples: one row of {@link #rowBytes} per image row. */
    private final byte[] data;
    private final int rowBytes;
    private final int[] palette;

    private PngImage(int width, int height, int bitDepth, int colorType, byte[] data, int[] palette) {
        this.width = width;
        this.height = height;
        this.bitDepth = bitDepth;
        this.colorType = colorType;
        this.channels = channelsFor(colorType);
        this.data = data;
        this.rowBytes = rowBytes(width, bitDepth, this.channels);
        this.palette = palette;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    /** Bits per sample (per palette index for palette images): 1, 2, 4, 8 or 16. */
    public int bitDepth() {
        return this.bitDepth;
    }

    public int colorType() {
        return this.colorType;
    }

    public boolean isGrayscale() {
        return this.colorType == COLOR_GRAY || this.colorType == COLOR_GRAY_ALPHA;
    }

    /**
     * Raw sample of a channel, in [0, 2^bitDepth - 1]. For palette images, channel 0 is the
     * palette index.
     */
    public int sample(int x, int y, int channel) {
        int rowStart = y * this.rowBytes;
        if (this.bitDepth == 8) {
            return this.data[rowStart + x * this.channels + channel] & 0xFF;
        }
        if (this.bitDepth == 16) {
            int i = rowStart + (x * this.channels + channel) * 2;
            return ((this.data[i] & 0xFF) << 8) | (this.data[i + 1] & 0xFF);
        }
        // Sub-byte depths only exist for single-channel color types.
        int bitIndex = x * this.bitDepth;
        int b = this.data[rowStart + (bitIndex >> 3)] & 0xFF;
        int shift = 8 - this.bitDepth - (bitIndex & 7);
        return (b >> shift) & ((1 << this.bitDepth) - 1);
    }

    /** Color of a pixel as 0xRRGGBB, 8 bits per channel (alpha ignored). */
    public int rgb(int x, int y) {
        switch (this.colorType) {
            case COLOR_PALETTE: {
                int index = this.sample(x, y, 0);
                return index < this.palette.length ? this.palette[index] : 0;
            }
            case COLOR_GRAY:
            case COLOR_GRAY_ALPHA: {
                int v = this.to8Bit(this.sample(x, y, 0));
                return (v << 16) | (v << 8) | v;
            }
            default: {
                int r = this.to8Bit(this.sample(x, y, 0));
                int g = this.to8Bit(this.sample(x, y, 1));
                int b = this.to8Bit(this.sample(x, y, 2));
                return (r << 16) | (g << 8) | b;
            }
        }
    }

    private int to8Bit(int value) {
        switch (this.bitDepth) {
            case 16:
                return value >> 8;
            case 8:
                return value;
            default:
                return value * 255 / ((1 << this.bitDepth) - 1);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------------------------------

    public static PngImage read(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        byte[] signature = new byte[8];
        in.readFully(signature);
        if (!Arrays.equals(signature, SIGNATURE)) {
            throw new IOException("not a PNG file");
        }

        int width = -1;
        int height = -1;
        int bitDepth = -1;
        int colorType = -1;
        int interlace = 0;
        int[] palette = new int[0];
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        boolean sawEnd = false;

        while (!sawEnd) {
            int length;
            try {
                length = in.readInt();
            } catch (EOFException e) {
                throw new IOException("truncated PNG (missing IEND)");
            }
            if (length < 0) {
                throw new IOException("invalid PNG chunk length");
            }
            byte[] typeBytes = new byte[4];
            in.readFully(typeBytes);
            String type = new String(typeBytes, StandardCharsets.US_ASCII);
            byte[] chunk = in.readNBytes(length);
            if (chunk.length != length) {
                throw new IOException("truncated PNG chunk " + type);
            }
            in.readInt(); // CRC, not verified: the ZIP container already checks integrity.

            switch (type) {
                case "IHDR": {
                    if (length < 13) {
                        throw new IOException("invalid IHDR");
                    }
                    width = readInt(chunk, 0);
                    height = readInt(chunk, 4);
                    bitDepth = chunk[8] & 0xFF;
                    colorType = chunk[9] & 0xFF;
                    if ((chunk[10] & 0xFF) != 0 || (chunk[11] & 0xFF) != 0) {
                        throw new IOException("unsupported PNG compression or filter method");
                    }
                    interlace = chunk[12] & 0xFF;
                    validateHeader(width, height, bitDepth, colorType, interlace);
                    break;
                }
                case "PLTE": {
                    palette = new int[length / 3];
                    for (int i = 0; i < palette.length; i++) {
                        palette[i] = ((chunk[i * 3] & 0xFF) << 16) | ((chunk[i * 3 + 1] & 0xFF) << 8) | (chunk[i * 3 + 2] & 0xFF);
                    }
                    break;
                }
                case "IDAT":
                    idat.write(chunk);
                    break;
                case "IEND":
                    sawEnd = true;
                    break;
                default:
                    // Ancillary chunk, ignored.
                    break;
            }
        }
        if (width < 0) {
            throw new IOException("PNG has no IHDR chunk");
        }
        if (colorType == COLOR_PALETTE && palette.length == 0) {
            throw new IOException("palette PNG has no PLTE chunk");
        }

        int channels = channelsFor(colorType);
        int rowBytes = rowBytes(width, bitDepth, channels);
        long decodedSize = (long) rowBytes * height;
        if (decodedSize > MAX_DECODED_BYTES) {
            throw new IOException("PNG is too large (" + width + "x" + height + ")");
        }
        byte[] data = new byte[(int) decodedSize];
        byte[] inflated = inflate(idat.toByteArray());
        int bytesPerPixel = Math.max(1, (bitDepth * channels) / 8);

        if (interlace == 0) {
            unfilter(inflated, 0, data, 0, rowBytes, rowBytes, height, bytesPerPixel);
        } else {
            deinterlace(inflated, data, width, height, bitDepth, channels, rowBytes, bytesPerPixel);
        }
        return new PngImage(width, height, bitDepth, colorType, data, palette);
    }

    private static void validateHeader(int width, int height, int bitDepth, int colorType, int interlace) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IOException("invalid PNG size " + width + "x" + height);
        }
        boolean valid = switch (colorType) {
            case COLOR_GRAY -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16;
            case COLOR_PALETTE -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            case COLOR_RGB, COLOR_GRAY_ALPHA, COLOR_RGBA -> bitDepth == 8 || bitDepth == 16;
            default -> false;
        };
        if (!valid) {
            throw new IOException("unsupported PNG color type " + colorType + " with bit depth " + bitDepth);
        }
        if (interlace != 0 && interlace != 1) {
            throw new IOException("unsupported PNG interlace method " + interlace);
        }
    }

    private static int channelsFor(int colorType) {
        return switch (colorType) {
            case COLOR_RGB -> 3;
            case COLOR_GRAY_ALPHA -> 2;
            case COLOR_RGBA -> 4;
            default -> 1;
        };
    }

    private static int rowBytes(int width, int bitDepth, int channels) {
        return (int) (((long) width * bitDepth * channels + 7) / 8);
    }

    private static int readInt(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16) | ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, compressed.length * 4));
        byte[] buffer = new byte[65536];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IOException("truncated PNG image data");
                }
                out.write(buffer, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IOException("corrupt PNG image data: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /**
     * Reverses the per-row filters of one (sub)image. Each source row is one filter byte followed
     * by {@code rowBytes} bytes; the result is written at {@code dstOffset} with {@code dstStride}.
     *
     * @return the number of source bytes consumed
     */
    private static int unfilter(byte[] src, int srcOffset, byte[] dst, int dstOffset, int rowBytes, int dstStride, int rows, int bpp) throws IOException {
        byte[] previous = new byte[rowBytes];
        byte[] current = new byte[rowBytes];
        int pos = srcOffset;
        for (int y = 0; y < rows; y++) {
            if (pos + 1 + rowBytes > src.length) {
                throw new IOException("truncated PNG image data");
            }
            int filter = src[pos++] & 0xFF;
            System.arraycopy(src, pos, current, 0, rowBytes);
            pos += rowBytes;
            switch (filter) {
                case 0:
                    break;
                case 1:
                    for (int i = bpp; i < rowBytes; i++) {
                        current[i] += current[i - bpp];
                    }
                    break;
                case 2:
                    for (int i = 0; i < rowBytes; i++) {
                        current[i] += previous[i];
                    }
                    break;
                case 3:
                    for (int i = 0; i < rowBytes; i++) {
                        int left = i >= bpp ? current[i - bpp] & 0xFF : 0;
                        current[i] += (byte) ((left + (previous[i] & 0xFF)) >>> 1);
                    }
                    break;
                case 4:
                    for (int i = 0; i < rowBytes; i++) {
                        int a = i >= bpp ? current[i - bpp] & 0xFF : 0;
                        int b = previous[i] & 0xFF;
                        int c = i >= bpp ? previous[i - bpp] & 0xFF : 0;
                        current[i] += (byte) paeth(a, b, c);
                    }
                    break;
                default:
                    throw new IOException("invalid PNG filter type " + filter);
            }
            System.arraycopy(current, 0, dst, dstOffset + y * dstStride, rowBytes);
            byte[] swap = previous;
            previous = current;
            current = swap;
        }
        return pos - srcOffset;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    private static final int[][] ADAM7 = {
            // startX, startY, stepX, stepY
            {0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}
    };

    private static void deinterlace(byte[] src, byte[] dst, int width, int height, int bitDepth, int channels, int rowBytes, int bpp) throws IOException {
        int pos = 0;
        int bitsPerPixel = bitDepth * channels;
        for (int[] pass : ADAM7) {
            int passWidth = (width - pass[0] + pass[2] - 1) / pass[2];
            int passHeight = (height - pass[1] + pass[3] - 1) / pass[3];
            if (passWidth <= 0 || passHeight <= 0) {
                continue;
            }
            int passRowBytes = rowBytes(passWidth, bitDepth, channels);
            byte[] passData = new byte[passRowBytes * passHeight];
            pos += unfilter(src, pos, passData, 0, passRowBytes, passRowBytes, passHeight, bpp);
            for (int py = 0; py < passHeight; py++) {
                int y = pass[1] + py * pass[3];
                for (int px = 0; px < passWidth; px++) {
                    int x = pass[0] + px * pass[2];
                    copyPixelBits(passData, py * passRowBytes, px, dst, y * rowBytes, x, bitsPerPixel);
                }
            }
        }
    }

    private static void copyPixelBits(byte[] src, int srcRow, int srcX, byte[] dst, int dstRow, int dstX, int bitsPerPixel) {
        if (bitsPerPixel >= 8) {
            int bytes = bitsPerPixel / 8;
            System.arraycopy(src, srcRow + srcX * bytes, dst, dstRow + dstX * bytes, bytes);
            return;
        }
        int srcBit = srcX * bitsPerPixel;
        int value = ((src[srcRow + (srcBit >> 3)] & 0xFF) >> (8 - bitsPerPixel - (srcBit & 7))) & ((1 << bitsPerPixel) - 1);
        int dstBit = dstX * bitsPerPixel;
        int shift = 8 - bitsPerPixel - (dstBit & 7);
        int index = dstRow + (dstBit >> 3);
        dst[index] = (byte) ((dst[index] & ~(((1 << bitsPerPixel) - 1) << shift)) | (value << shift));
    }
}
