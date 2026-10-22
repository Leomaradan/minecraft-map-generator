package com.leomaradan.mapgen.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Contents of {@code biomes.json}: biome map color to biome, with optional surface blocks and
 * substitute biomes.
 * <pre>{@code
 * { "#3A7D22": "minecraft:forest",
 *   "#FF00FF": { "biome": "mymap:crystal_plains", "top": "minecraft:moss_block", "filler": "minecraft:dirt", "filler_depth": 4,
 *                "biome_river": "minecraft:river", "biome_underground": "minecraft:lush_caves" } }
 * }</pre>
 *
 * Every biome the map can produce is a <em>slot</em>: slot {@code i} for {@code i < entries.size()}
 * is the biome of entry {@code i}, the substitutes come after.
 *
 * @param slots biomes and surfaces of every slot
 */
public record BiomeMapping(List<Entry> entries, List<Variant> slots) {
    /** Default width of the {@code biome_river} banks around the water, in blocks. */
    public static final int DEFAULT_RIVER_WIDTH = 4;
    /** Default width of the {@code biome_border} band, in blocks. */
    public static final int DEFAULT_BORDER_WIDTH = 4;
    /** Default depth below the ground where {@code biome_underground} starts, in blocks. */
    public static final int DEFAULT_UNDERGROUND_DEPTH = 16;
    /** Default height of {@code biome_mountain} above the sea level, in blocks. */
    public static final int DEFAULT_MOUNTAIN_ABOVE_SEA = 64;

    private static final String[] SURFACE_KEYS = {"top", "filler", "underwater", "filler_depth", "snow"};

    /**
     * A biome with its surface blocks.
     *
     * @param top         surface block, null for the default derived from the biome
     * @param filler      block under the surface, null for the default derived from the biome
     * @param underwater  surface block under water or lava, null for the default
     * @param fillerDepth thickness of top + filler layers, null for the default
     * @param snow        where snow and ice may form, null for {@code snow} of map.json
     * @param origin      where it comes from in biomes.json, for messages (e.g. "#3A7D22.biome_river")
     */
    public record Variant(String biome, String top, String filler, String underwater, Integer fillerDepth, MapConfig.Snow snow,
                          String origin) {
    }

    /**
     * One mapped color. The substitute slots are -1 when not configured.
     *
     * @param riverWidth       width of the river banks (land near water using the river biome) in blocks
     * @param borderWidth      width of the border band in blocks
     * @param mountainY        Y from which the mountain biome is used, null for sea level + 64
     * @param undergroundDepth depth below the ground where the underground biome starts
     */
    public record Entry(int color, String biome, String top, String filler, String underwater, Integer fillerDepth,
                        int riverSlot, int borderSlot, int mountainSlot, int undergroundSlot,
                        int riverWidth, int borderWidth, Integer mountainY, int undergroundDepth) {
        public boolean hasBorder() {
            return this.borderSlot >= 0;
        }

        public boolean hasRiver() {
            return this.riverSlot >= 0;
        }
    }

    public static final BiomeMapping EMPTY = new BiomeMapping(List.of(), List.of());

    public boolean hasBorders() {
        return this.entries.stream().anyMatch(Entry::hasBorder);
    }

    public boolean hasRivers() {
        return this.entries.stream().anyMatch(Entry::hasRiver);
    }

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
        Map<Integer, JsonElement> values = new LinkedHashMap<>();
        Map<Integer, String> paths = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            String key = e.getKey();
            if (key.equals("$schema")) {
                continue;
            }
            String p = fileName + "[\"" + key + "\"]";
            Integer color = parseColor(key);
            if (color == null) {
                report.error(p + ": \"" + key + "\" is not a color (use #RRGGBB, RRGGBB, 0xRRGGBB or r,g,b)");
                continue;
            }
            if (values.containsKey(color)) {
                report.error(p + ": color " + formatColor(color) + " is mapped twice");
                continue;
            }
            values.put(color, e.getValue());
            paths.put(color, p);
        }

        // Base slots first (slot i is entry i), substitutes after.
        List<Entry> entries = new ArrayList<>();
        List<Variant> slots = new ArrayList<>();
        List<Variant> substitutes = new ArrayList<>();
        int firstSubstitute = values.size();
        for (Map.Entry<Integer, JsonElement> e : values.entrySet()) {
            Entry entry = parseEntry(e.getKey(), e.getValue(), paths.get(e.getKey()), firstSubstitute, slots, substitutes, report);
            if (entry != null) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            report.error(fileName + " does not map any color");
        }
        if (entries.size() != firstSubstitute) {
            // Some entries were rejected: the slot numbers are off, but the mapping is not used anyway.
            return new BiomeMapping(List.copyOf(entries), List.copyOf(slots));
        }
        slots.addAll(substitutes);
        return new BiomeMapping(List.copyOf(entries), List.copyOf(slots));
    }

    private static Entry parseEntry(int color, JsonElement value, String p, int firstSubstitute, List<Variant> slots, List<Variant> substitutes,
                                    ValidationReport report) {
        String origin = formatColor(color);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String biome = value.getAsString();
            if (!Json.isValidId(biome)) {
                report.error(p + ": \"" + biome + "\" is not a valid biome id");
                return null;
            }
            slots.add(new Variant(Json.normalizeId(biome), null, null, null, null, null, origin));
            return new Entry(color, Json.normalizeId(biome), null, null, null, null, -1, -1, -1, -1,
                    DEFAULT_RIVER_WIDTH, DEFAULT_BORDER_WIDTH, null, DEFAULT_UNDERGROUND_DEPTH);
        }
        if (!value.isJsonObject()) {
            report.error(p + " must be a biome id or an object");
            return null;
        }
        JsonObject o = value.getAsJsonObject();
        Json.checkKnownKeys(o, p, report, "biome", "top", "filler", "underwater", "filler_depth", "snow", "biome_river", "biome_border", "biome_mountain",
                "biome_underground", "river_width", "border_width", "mountain_y", "underground_depth");
        Variant base = parseVariant(o, p, origin, report);
        if (base == null) {
            return null;
        }
        int river = substitute(o, "biome_river", p, origin, firstSubstitute, substitutes, true, report);
        int border = substitute(o, "biome_border", p, origin, firstSubstitute, substitutes, true, report);
        int mountain = substitute(o, "biome_mountain", p, origin, firstSubstitute, substitutes, true, report);
        int underground = substitute(o, "biome_underground", p, origin, firstSubstitute, substitutes, false, report);

        int riverWidth = Json.integer(o, "river_width", DEFAULT_RIVER_WIDTH, p, report);
        if (riverWidth < 0 || riverWidth > 64) {
            report.error(p + ".river_width must be between 0 and 64");
            riverWidth = DEFAULT_RIVER_WIDTH;
        }
        int borderWidth = Json.integer(o, "border_width", DEFAULT_BORDER_WIDTH, p, report);
        if (borderWidth < 1 || borderWidth > 64) {
            report.error(p + ".border_width must be between 1 and 64");
            borderWidth = DEFAULT_BORDER_WIDTH;
        }
        int undergroundDepth = Json.integer(o, "underground_depth", DEFAULT_UNDERGROUND_DEPTH, p, report);
        if (undergroundDepth < 0) {
            report.error(p + ".underground_depth must not be negative");
            undergroundDepth = DEFAULT_UNDERGROUND_DEPTH;
        }
        for (String[] rule : new String[][]{{"river_width", "biome_river"}, {"border_width", "biome_border"}, {"mountain_y", "biome_mountain"}, {"underground_depth", "biome_underground"}}) {
            if (Json.has(o, rule[0]) && !Json.has(o, rule[1])) {
                report.warn(p + "." + rule[0] + " has no effect without " + rule[1]);
            }
        }
        slots.add(base);
        return new Entry(color, base.biome(), base.top(), base.filler(), base.underwater(), base.fillerDepth(), river, border, mountain, underground,
                riverWidth, borderWidth, Json.integer(o, "mountain_y", null, p, report), undergroundDepth);
    }

    /** Reads {@code {"biome": ..., "top": ..., ...}}; null if invalid. */
    private static Variant parseVariant(JsonObject o, String p, String origin, ValidationReport report) {
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
        return new Variant(biome, Json.id(o, "top", null, p, report), Json.id(o, "filler", null, p, report), Json.id(o, "underwater", null, p, report),
                depth, MapConfig.Snow.parse(o, "snow", p, report), origin);
    }

    /**
     * Reads a substitute biome (a biome id, or an object like an entry when {@code withSurface}) and
     * appends it to {@code substitutes}.
     *
     * @return its slot, -1 when absent or invalid
     */
    private static int substitute(JsonObject o, String key, String p, String origin, int firstSubstitute, List<Variant> substitutes,
                                  boolean withSurface, ValidationReport report) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return -1;
        }
        String sp = p + "." + key;
        String so = origin + "." + key;
        Variant variant;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            if (!Json.isValidId(e.getAsString())) {
                report.error(sp + ": \"" + e.getAsString() + "\" is not a valid biome id");
                return -1;
            }
            variant = new Variant(Json.normalizeId(e.getAsString()), null, null, null, null, null, so);
        } else if (withSurface && e.isJsonObject()) {
            Json.checkKnownKeys(e.getAsJsonObject(), sp, report, concat("biome", SURFACE_KEYS));
            variant = parseVariant(e.getAsJsonObject(), sp, so, report);
            if (variant == null) {
                return -1;
            }
        } else {
            report.error(sp + (withSurface ? " must be a biome id or an object" : " must be a biome id"));
            return -1;
        }
        substitutes.add(variant);
        return firstSubstitute + substitutes.size() - 1;
    }

    private static String[] concat(String first, String[] rest) {
        String[] all = new String[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
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
