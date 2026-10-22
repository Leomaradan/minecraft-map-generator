package com.leomaradan.mapgen.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Contents of {@code biomes.json}: biome map color to biome, with optional surface blocks.
 * <pre>{@code
 * { "#3A7D22": "minecraft:forest",
 *   "#FF00FF": { "biome": "mymap:crystal_plains", "top": "minecraft:moss_block", "filler": "minecraft:dirt", "filler_depth": 4 } }
 * }</pre>
 */
public record BiomeMapping(List<Entry> entries) {

    /**
     * @param top          surface block, null for the default derived from the biome
     * @param filler       block under the surface, null for the default derived from the biome
     * @param underwater   surface block under water or lava, null for the default
     * @param fillerDepth  thickness of top + filler layers, null for the default
     */
    public record Entry(int color, String biome, String top, String filler, String underwater, Integer fillerDepth) {
    }

    public static final BiomeMapping EMPTY = new BiomeMapping(List.of());

    public int indexOfColor(int rgb) {
        for (int i = 0; i < this.entries.size(); i++) {
            if (this.entries.get(i).color() == rgb) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the mapped color closest to {@code rgb} (euclidean distance in RGB). */
    public int nearestIndex(int rgb) {
        int best = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < this.entries.size(); i++) {
            long d = colorDistance(rgb, this.entries.get(i).color());
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    static long colorDistance(int a, int b) {
        long dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        long dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        long db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    public static BiomeMapping parse(JsonObject root, String fileName, ValidationReport report) {
        Map<Integer, Entry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            String key = e.getKey();
            String p = fileName + "[\"" + key + "\"]";
            Integer color = parseColor(key);
            if (color == null) {
                report.error(p + ": \"" + key + "\" is not a color (use #RRGGBB, RRGGBB, 0xRRGGBB or r,g,b)");
                continue;
            }
            Entry entry = parseEntry(color, e.getValue(), p, report);
            if (entry == null) {
                continue;
            }
            if (entries.containsKey(color)) {
                report.error(p + ": color " + formatColor(color) + " is mapped twice");
                continue;
            }
            entries.put(color, entry);
        }
        if (entries.isEmpty()) {
            report.error(fileName + " does not map any color");
        }
        return new BiomeMapping(List.copyOf(new ArrayList<>(entries.values())));
    }

    private static Entry parseEntry(int color, JsonElement value, String p, ValidationReport report) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String biome = value.getAsString();
            if (!Json.isValidId(biome)) {
                report.error(p + ": \"" + biome + "\" is not a valid biome id");
                return null;
            }
            return new Entry(color, Json.normalizeId(biome), null, null, null, null);
        }
        if (!value.isJsonObject()) {
            report.error(p + " must be a biome id or an object");
            return null;
        }
        JsonObject o = value.getAsJsonObject();
        Json.checkKnownKeys(o, p, report, "biome", "top", "filler", "underwater", "filler_depth");
        String biome = Json.id(o, "biome", null, p, report);
        if (biome == null) {
            report.error(p + ".biome is required");
            return null;
        }
        Integer depth = Json.integer(o, "filler_depth", null, p, report);
        if (depth != null && (depth < 0 || depth > 64)) {
            report.error(p + ".filler_depth must be between 0 and 64");
            depth = null;
        }
        return new Entry(color, biome, Json.id(o, "top", null, p, report), Json.id(o, "filler", null, p, report),
                Json.id(o, "underwater", null, p, report), depth);
    }

    /** Parses "#RRGGBB", "RRGGBB", "0xRRGGBB" or "r,g,b". */
    public static Integer parseColor(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.contains(",")) {
            String[] parts = s.split(",");
            if (parts.length != 3) {
                return null;
            }
            int rgb = 0;
            for (String part : parts) {
                try {
                    int c = Integer.parseInt(part.trim());
                    if (c < 0 || c > 255) {
                        return null;
                    }
                    rgb = (rgb << 8) | c;
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            return rgb;
        }
        if (s.startsWith("#")) {
            s = s.substring(1);
        } else if (s.startsWith("0x")) {
            s = s.substring(2);
        }
        if (s.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static String formatColor(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }
}
