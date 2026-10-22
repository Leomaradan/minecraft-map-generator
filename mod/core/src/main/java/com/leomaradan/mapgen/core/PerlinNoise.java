package com.leomaradan.mapgen.core;

import java.util.Arrays;
import java.util.SplittableRandom;

/** 3D gradient noise (Ken Perlin's improved noise), with a quantile table to spread its values evenly over [0, 1). */
final class PerlinNoise {
    private static final int SAMPLES = 4096;
    /** Sorted noise values at random points: the rank of a value gives its quantile. */
    private static final double[] SORTED = sortedSamples();

    private final int[] permutation = new int[512];

    PerlinNoise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) {
            this.permutation[i] = p[i & 255];
        }
    }

    /** Noise in about [-1, 1], 0 at integer coordinates. */
    double noise(double x, double y, double z) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        int zi = (int) Math.floor(z);
        double xf = x - xi;
        double yf = y - yi;
        double zf = z - zi;
        int[] p = this.permutation;
        int a = p[xi & 255] + (yi & 255);
        int aa = p[a] + (zi & 255);
        int ab = p[a + 1] + (zi & 255);
        int b = p[(xi + 1) & 255] + (yi & 255);
        int ba = p[b] + (zi & 255);
        int bb = p[b + 1] + (zi & 255);
        double u = fade(xf);
        double v = fade(yf);
        double w = fade(zf);
        return lerp(w,
                lerp(v, lerp(u, grad(p[aa], xf, yf, zf), grad(p[ba], xf - 1, yf, zf)),
                        lerp(u, grad(p[ab], xf, yf - 1, zf), grad(p[bb], xf - 1, yf - 1, zf))),
                lerp(v, lerp(u, grad(p[aa + 1], xf, yf, zf - 1), grad(p[ba + 1], xf - 1, yf, zf - 1)),
                        lerp(u, grad(p[ab + 1], xf, yf - 1, zf - 1), grad(p[bb + 1], xf - 1, yf - 1, zf - 1))));
    }

    /** Noise as an evenly distributed value in [0, 1): each sub-range is as frequent as its length. */
    double uniform(double x, double y, double z) {
        int index = Arrays.binarySearch(SORTED, this.noise(x, y, z));
        int rank = index >= 0 ? index : -index - 1;
        return Math.min(rank, SAMPLES - 1) / (double) SAMPLES;
    }

    private static double[] sortedSamples() {
        PerlinNoise noise = new PerlinNoise(0);
        SplittableRandom random = new SplittableRandom(1);
        double[] samples = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            samples[i] = noise.noise(random.nextDouble(256), random.nextDouble(256), random.nextDouble(256));
        }
        Arrays.sort(samples);
        return samples;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
