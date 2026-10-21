package com.leomaradan.mapgen.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** PNG encoder for tests: any color type / bit depth, every filter type, optional Adam7. */
final class TestPng {
    private TestPng() {
    }

    /** Pixel source: returns the sample of {@code channel} at (x, y). */
    interface Samples {
        int get(int x, int y, int channel);
    }

    static byte[] gray(int width, int height, int bitDepth, Samples samples) {
        return encode(width, height, bitDepth, 0, 1, samples, null, false);
    }

    static byte[] rgb(int width, int height, Samples samples) {
        return encode(width, height, 8, 2, 3, samples, null, false);
    }

    static byte[] encode(int width, int height, int bitDepth, int colorType, int channels, Samples samples, int[] palette, boolean interlaced) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            int[][] passes = interlaced
                    ? new int[][]{{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}}
                    : new int[][]{{0, 0, 1, 1}};
            int bpp = Math.max(1, bitDepth * channels / 8);
            int filterCounter = 0;
            for (int[] pass : passes) {
                int pw = (width - pass[0] + pass[2] - 1) / pass[2];
                int ph = (height - pass[1] + pass[3] - 1) / pass[3];
                if (pw <= 0 || ph <= 0) {
                    continue;
                }
                int rowBytes = (pw * bitDepth * channels + 7) / 8;
                byte[] previous = new byte[rowBytes];
                for (int py = 0; py < ph; py++) {
                    byte[] row = new byte[rowBytes];
                    int y = pass[1] + py * pass[3];
                    for (int px = 0; px < pw; px++) {
                        int x = pass[0] + px * pass[2];
                        for (int c = 0; c < channels; c++) {
                            writeSample(row, px * channels + c, bitDepth, samples.get(x, y, c));
                        }
                    }
                    int filter = filterCounter++ % 5;
                    raw.write(filter);
                    raw.write(filterRow(filter, row, previous, bpp));
                    previous = row;
                }
            }

            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream dos = new DeflaterOutputStream(compressed, new Deflater(9))) {
                dos.write(raw.toByteArray());
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(ihdr);
            h.writeInt(width);
            h.writeInt(height);
            h.writeByte(bitDepth);
            h.writeByte(colorType);
            h.writeByte(0);
            h.writeByte(0);
            h.writeByte(interlaced ? 1 : 0);
            chunk(out, "IHDR", ihdr.toByteArray());
            if (palette != null) {
                byte[] plte = new byte[palette.length * 3];
                for (int i = 0; i < palette.length; i++) {
                    plte[i * 3] = (byte) (palette[i] >> 16);
                    plte[i * 3 + 1] = (byte) (palette[i] >> 8);
                    plte[i * 3 + 2] = (byte) palette[i];
                }
                chunk(out, "PLTE", plte);
            }
            // Split the data into two IDAT chunks to exercise concatenation.
            byte[] data = compressed.toByteArray();
            int half = data.length / 2;
            chunk(out, "IDAT", java.util.Arrays.copyOfRange(data, 0, half));
            chunk(out, "tEXt", "Comment\0test".getBytes(StandardCharsets.ISO_8859_1));
            chunk(out, "IDAT", java.util.Arrays.copyOfRange(data, half, data.length));
            chunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeSample(byte[] row, int sampleIndex, int bitDepth, int value) {
        if (bitDepth == 16) {
            row[sampleIndex * 2] = (byte) (value >> 8);
            row[sampleIndex * 2 + 1] = (byte) value;
        } else if (bitDepth == 8) {
            row[sampleIndex] = (byte) value;
        } else {
            int bit = sampleIndex * bitDepth;
            int shift = 8 - bitDepth - (bit & 7);
            row[bit >> 3] |= (byte) ((value & ((1 << bitDepth) - 1)) << shift);
        }
    }

    private static byte[] filterRow(int filter, byte[] row, byte[] previous, int bpp) {
        byte[] out = new byte[row.length];
        for (int i = 0; i < row.length; i++) {
            int x = row[i] & 0xFF;
            int a = i >= bpp ? row[i - bpp] & 0xFF : 0;
            int b = previous[i] & 0xFF;
            int c = i >= bpp ? previous[i - bpp] & 0xFF : 0;
            int predictor = switch (filter) {
                case 1 -> a;
                case 2 -> b;
                case 3 -> (a + b) >>> 1;
                case 4 -> paeth(a, b, c);
                default -> 0;
            };
            out[i] = (byte) (x - predictor);
        }
        return out;
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

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        d.write(typeBytes);
        d.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        d.writeInt((int) crc.getValue());
    }
}
