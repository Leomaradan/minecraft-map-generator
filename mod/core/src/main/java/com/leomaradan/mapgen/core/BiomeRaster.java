package com.leomaradan.mapgen.core;

import com.leomaradan.mapgen.core.png.PngImage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Biome map: each pixel color resolves to an index in the {@link BiomeMapping}. Colors missing
 * from the mapping resolve to the nearest mapped color. The color table is built once when the map
 * is loaded, so lookups are read-only and thread safe.
 */
public final class BiomeRaster {
    private static final int MAX_LISTED_COLORS = 8;

    private final PngImage image;
    /** Sorted distinct colors of the image, and the mapping index of each. */
    private final int[] colors;
    private final int[] indexes;

    private BiomeRaster(PngImage image, int[] colors, int[] indexes) {
        this.image = image;
        this.colors = colors;
        this.indexes = indexes;
    }

    public static BiomeRaster build(PngImage image, BiomeMapping mapping, String fileName, ValidationReport report) {
        Map<Integer, long[]> counts = new HashMap<>();
        int lastColor = -1;
        long[] lastCount = null;
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int rgb = image.rgb(x, y);
                if (rgb != lastColor || lastCount == null) {
                    lastColor = rgb;
                    lastCount = counts.computeIfAbsent(rgb, k -> new long[1]);
                }
                lastCount[0]++;
            }
        }

        int[] colors = counts.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        int[] indexes = new int[colors.length];
        List<String> unmapped = new ArrayList<>();
        for (int i = 0; i < colors.length; i++) {
            int index = mapping.indexOfColor(colors[i]);
            if (index < 0) {
                index = mapping.nearestIndex(colors[i]);
                if (index >= 0) {
                    unmapped.add(BiomeMapping.formatColor(colors[i]) + " (" + counts.get(colors[i])[0] + " px) -> "
                            + mapping.entries().get(index).biome());
                }
            }
            indexes[i] = index;
        }
        if (!unmapped.isEmpty()) {
            StringBuilder sb = new StringBuilder(fileName).append(": ").append(unmapped.size())
                    .append(" color(s) are not in the biome mapping and use the nearest mapped color: ");
            for (int i = 0; i < Math.min(MAX_LISTED_COLORS, unmapped.size()); i++) {
                sb.append(i == 0 ? "" : ", ").append(unmapped.get(i));
            }
            if (unmapped.size() > MAX_LISTED_COLORS) {
                sb.append(", ...");
            }
            report.warn(sb.toString());
        }
        return new BiomeRaster(image, colors, indexes);
    }

    public int width() {
        return this.image.width();
    }

    public int height() {
        return this.image.height();
    }

    /** Index in the biome mapping of the pixel, or -1 if the mapping is empty. */
    public int index(int x, int y) {
        int i = Arrays.binarySearch(this.colors, this.image.rgb(x, y));
        return i >= 0 ? this.indexes[i] : -1;
    }
}
