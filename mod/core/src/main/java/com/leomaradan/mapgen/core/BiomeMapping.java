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

    private static final String BIOME = "biome";
    private static final String TOP = "top";
    private static final String FILLER = "filler";
    private static final String UNDERWATER = "underwater";
    private static final String FILLER_DEPTH = "filler_depth";
    private static final String SNOW = "snow";
    private static final String FEATURES = "features";
    private static final String CARVERS = "carvers";
    private static final String BIOME_RIVER = "biome_river";
    private static final String BIOME_BORDER = "biome_border";
    private static final String BIOME_MOUNTAIN = "biome_mountain";
    private static final String BIOME_UNDERGROUND = "biome_underground";
    /** Keys of a variant besides {@code biome}: surface blocks and decoration. */
    private static final String[] SURFACE_KEYS = {TOP, FILLER, UNDERWATER, FILLER_DEPTH, SNOW, FEATURES, CARVERS};
    private static final String RIVER_WIDTH = "river_width";
    private static final String BORDER_WIDTH = "border_width";
    private static final String MOUNTAIN_Y = "mountain_y";
    private static final String UNDERGROUND_DEPTH = "underground_depth";
    private static final String[] SUBSTITUTE_KEYS = {BIOME_RIVER, BIOME_BORDER, BIOME_MOUNTAIN, BIOME_UNDERGROUND, RIVER_WIDTH, BORDER_WIDTH,
            MOUNTAIN_Y, UNDERGROUND_DEPTH};

    /**
     * A biome with its surface blocks and decoration.
     *
     * @param top         surface blocks, null for the default derived from the biome
     * @param filler      blocks under the surface, null for the default derived from the biome
     * @param underwater  surface blocks under water or lava, null for the default
     * @param fillerDepth thickness of top + filler layers, null for the default
     * @param snow        where snow and ice may form, null for {@code snow} of map.json
     * @param features    biome features filter, null for {@code decoration.features} of map.json
     * @param carvers     biome carvers, null for {@code decoration.carvers} of map.json
     * @param origin      where it comes from in biomes.json, for messages (e.g. "#3A7D22.biome_river")
     */
    public record Variant(String biome, BlockPalette top, BlockPalette filler, BlockPalette underwater, Integer fillerDepth, MapConfig.Snow snow,
                          MapConfig.Features features, MapConfig.Carvers carvers, String origin) {
        static Variant of(String biome, String origin) {
            return new Variant(biome, null, null, null, null, null, null, null, origin);
        }

        /** Block ids named by the surface layers. */
        public List<String> blockIds() {
            List<String> ids = new ArrayList<>();
            for (BlockPalette palette : new BlockPalette[]{this.top, this.filler, this.underwater}) {
                if (palette != null) {
                    ids.addAll(palette.ids());
                }
            }
            return ids;
        }
    }

    /**
     * One mapped color. The substitute slots are -1 when not configured.
     *
     * @param riverWidth       width of the river banks (land near water using the river biome) in blocks
     * @param borderWidth      width of the border band in blocks
     * @param mountainY        Y from which the mountain biome is used, null for sea level + 64
     * @param undergroundDepth depth below the ground where the underground biome starts
     */
    public record Entry(int color, String biome, int riverSlot, int borderSlot, int mountainSlot, int undergroundSlot,
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

    public boolean hasFeatureOverrides() {
        return this.slots.stream().anyMatch(v -> v.features() != null);
    }

    public boolean hasCarverOverrides() {
        return this.slots.stream().anyMatch(v -> v.carvers() != null);
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
            slots.add(Variant.of(Json.normalizeId(biome), origin));
            return new Entry(color, Json.normalizeId(biome), -1, -1, -1, -1,
                    DEFAULT_RIVER_WIDTH, DEFAULT_BORDER_WIDTH, null, DEFAULT_UNDERGROUND_DEPTH);
        }
        if (!value.isJsonObject()) {
            report.error(p + " must be a biome id or an object");
            return null;
        }
        JsonObject o = value.getAsJsonObject();
        Json.checkKnownKeys(o, p, report, concat(concat(BIOME, SURFACE_KEYS), SUBSTITUTE_KEYS));
        Variant base = parseVariant(o, p, origin, report);
        if (base == null) {
            return null;
        }
        int river = substitute(o, BIOME_RIVER, p, origin, firstSubstitute, substitutes, true, report);
        int border = substitute(o, BIOME_BORDER, p, origin, firstSubstitute, substitutes, true, report);
        int mountain = substitute(o, BIOME_MOUNTAIN, p, origin, firstSubstitute, substitutes, true, report);
        int underground = substitute(o, BIOME_UNDERGROUND, p, origin, firstSubstitute, substitutes, false, report);

        int riverWidth = Json.integer(o, RIVER_WIDTH, DEFAULT_RIVER_WIDTH, p, report);
        if (riverWidth < 0 || riverWidth > 64) {
            report.error(p + ".river_width must be between 0 and 64");
            riverWidth = DEFAULT_RIVER_WIDTH;
        }
        int borderWidth = Json.integer(o, BORDER_WIDTH, DEFAULT_BORDER_WIDTH, p, report);
        if (borderWidth < 1 || borderWidth > 64) {
            report.error(p + ".border_width must be between 1 and 64");
            borderWidth = DEFAULT_BORDER_WIDTH;
        }
        int undergroundDepth = Json.integer(o, UNDERGROUND_DEPTH, DEFAULT_UNDERGROUND_DEPTH, p, report);
        if (undergroundDepth < 0) {
            report.error(p + ".underground_depth must not be negative");
            undergroundDepth = DEFAULT_UNDERGROUND_DEPTH;
        }
        for (String[] rule : new String[][]{{RIVER_WIDTH, BIOME_RIVER}, {BORDER_WIDTH, BIOME_BORDER}, {MOUNTAIN_Y, BIOME_MOUNTAIN}, {UNDERGROUND_DEPTH, BIOME_UNDERGROUND}}) {
            if (Json.has(o, rule[0]) && !Json.has(o, rule[1])) {
                report.warn(p + "." + rule[0] + " has no effect without " + rule[1]);
            }
        }
        slots.add(base);
        return new Entry(color, base.biome(), river, border, mountain, underground,
                riverWidth, borderWidth, Json.integer(o, MOUNTAIN_Y, null, p, report), undergroundDepth);
    }

    /** Reads {@code {"biome": ..., "top": ..., ...}}; null if invalid. */
    private static Variant parseVariant(JsonObject o, String p, String origin, ValidationReport report) {
        String biome = Json.id(o, BIOME, null, p, report);
        if (biome == null) {
            report.error(p + ".biome is required");
            return null;
        }
        Integer depth = Json.integer(o, FILLER_DEPTH, null, p, report);
        if (depth != null && (depth < 0 || depth > 64)) {
            report.error(p + ".filler_depth must be between 0 and 64");
            depth = null;
        }
        return new Variant(biome, BlockPalette.parse(o, TOP, p, report), BlockPalette.parse(o, FILLER, p, report),
                BlockPalette.parse(o, UNDERWATER, p, report), depth, MapConfig.Snow.parse(o, SNOW, p, report),
                MapConfig.Features.parse(o, FEATURES, p, report), MapConfig.Carvers.parse(o, CARVERS, p, report), origin);
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
            variant = Variant.of(Json.normalizeId(e.getAsString()), so);
        } else if (withSurface && e.isJsonObject()) {
            Json.checkKnownKeys(e.getAsJsonObject(), sp, report, concat(BIOME, SURFACE_KEYS));
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

    private static String[] concat(String[] first, String[] rest) {
        String[] all = new String[first.length + rest.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(rest, 0, all, first.length, rest.length);
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
