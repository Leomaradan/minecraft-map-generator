package com.leomaradan.mapgen.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One or several blocks for a layer, picked by position. Accepted forms:
 * <pre>{@code
 * "minecraft:stone"
 * ["minecraft:stone", {"block": "minecraft:dirt", "weight": 3}]
 * {"blocks": [...], "method": "perlin", "scale": 16}
 * }</pre>
 * The same position always gives the same block.
 */
public final class BlockPalette {
    public static final double DEFAULT_SCALE = 16;
    private static final String BLOCKS = "blocks";
    private static final String BLOCK = "block";
    private static final String WEIGHT = "weight";
    /**
     * Shift of the noise coordinates, so that positions don't fall on the noise lattice (where it is
     * always 0) when the scale divides them, e.g. at scale 1.
     */
    private static final double OFFSET_X = 0.3183098861837907;
    private static final double OFFSET_Y = 0.5772156649015329;
    private static final double OFFSET_Z = 0.7071067811865476;

    /** @param weight relative probability, greater than 0 */
    public record Choice(String block, double weight) {
    }

    public enum Method {
        /** Each block is picked independently. */
        RANDOM,
        /** Smooth 3D noise: blocks form patches about {@code scale} blocks wide. */
        PERLIN
    }

    private final List<Choice> blocks;
    private final Method method;
    private final double scale;
    private final long seed;
    /** Upper bound of each choice in [0, 1). */
    private final double[] cumulative;
    private final PerlinNoise noise;

    public BlockPalette(List<Choice> blocks, Method method, double scale, long seed) {
        this.blocks = List.copyOf(blocks);
        this.method = method;
        this.scale = scale;
        this.seed = seed;
        // Relative to the largest weight: huge weights can't overflow the sum.
        double max = this.blocks.stream().mapToDouble(Choice::weight).max().orElse(1);
        double total = 0;
        for (Choice c : this.blocks) {
            total += c.weight() / max;
        }
        this.cumulative = new double[this.blocks.size()];
        double sum = 0;
        for (int i = 0; i < this.blocks.size(); i++) {
            sum += this.blocks.get(i).weight() / max;
            this.cumulative[i] = sum / total;
        }
        this.noise = method == Method.PERLIN && this.blocks.size() > 1 ? new PerlinNoise(seed) : null;
    }

    public static BlockPalette of(String block) {
        return new BlockPalette(List.of(new Choice(block, 1)), Method.RANDOM, DEFAULT_SCALE, 0);
    }

    public List<Choice> blocks() {
        return this.blocks;
    }

    public Method method() {
        return this.method;
    }

    public double scale() {
        return this.scale;
    }

    /** Block ids, in order (the indices returned by {@link #pick}). */
    public List<String> ids() {
        return this.blocks.stream().map(Choice::block).toList();
    }

    /** Index of the block at a position. */
    public int pick(int x, int y, int z) {
        if (this.blocks.size() == 1) {
            return 0;
        }
        double u = this.method == Method.PERLIN
                ? this.noise.uniform(x / this.scale + OFFSET_X, y / this.scale + OFFSET_Y, z / this.scale + OFFSET_Z)
                : this.random(x, y, z);
        for (int i = 0; i < this.cumulative.length - 1; i++) {
            if (u < this.cumulative[i]) {
                return i;
            }
        }
        return this.cumulative.length - 1;
    }

    /** Value in [0, 1) from a hash of the position. */
    private double random(int x, int y, int z) {
        long h = this.seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    /**
     * Reads a block or palette; null when absent or invalid. The seed comes from the path, so two
     * layers with the same blocks still get different patterns.
     */
    static BlockPalette parse(JsonObject parent, String key, String path, ValidationReport report) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        String p = path + "." + key;
        long seed = p.hashCode();
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String id = blockId(e, p, report);
            return id == null ? null : new BlockPalette(List.of(new Choice(id, 1)), Method.RANDOM, DEFAULT_SCALE, seed);
        }
        if (e.isJsonArray()) {
            List<Choice> choices = choices(e.getAsJsonArray(), p, report);
            return choices.isEmpty() ? null : new BlockPalette(choices, Method.RANDOM, DEFAULT_SCALE, seed);
        }
        if (e.isJsonObject()) {
            return parseObject(e.getAsJsonObject(), p, seed, report);
        }
        report.error(p + " must be a block id, a list of blocks, or {\"blocks\": [...], \"method\": ...}");
        return null;
    }

    /** {@code {"blocks": [...], "method": ..., "scale": ...}}; null when invalid. */
    private static BlockPalette parseObject(JsonObject o, String p, long seed, ValidationReport report) {
        Json.checkKnownKeys(o, p, report, BLOCKS, "method", "scale");
        JsonArray array = Json.array(o, BLOCKS, p, report);
        if (array == null && !Json.has(o, BLOCKS)) {
            report.error(p + ".blocks is required");
        }
        List<Choice> choices = array == null ? List.of() : choices(array, p + "." + BLOCKS, report);
        Method method = switch (Json.string(o, "method", "random", p, report).toLowerCase(Locale.ROOT)) {
            case "random" -> Method.RANDOM;
            case "perlin" -> Method.PERLIN;
            default -> {
                report.error(p + ".method must be \"random\" or \"perlin\"");
                yield null;
            }
        };
        double scale = Json.number(o, "scale", DEFAULT_SCALE, p, report);
        if (scale <= 0) {
            report.error(p + ".scale must be greater than 0");
            scale = DEFAULT_SCALE;
        }
        return choices.isEmpty() || method == null ? null : new BlockPalette(choices, method, scale, seed);
    }

    /** The choices of a list; empty when the list is empty or has an invalid element. */
    private static List<Choice> choices(JsonArray array, String p, ValidationReport report) {
        if (array.isEmpty()) {
            report.error(p + " must not be empty");
            return List.of();
        }
        List<Choice> choices = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Choice choice = choice(array.get(i), p + "[" + i + "]", report);
            if (choice != null) {
                choices.add(choice);
            }
        }
        return choices.size() == array.size() ? choices : List.of();
    }

    /** A block id (weight 1) or {@code {"block": ..., "weight": ...}}; null when invalid. */
    private static Choice choice(JsonElement e, String p, ValidationReport report) {
        if (!e.isJsonObject()) {
            String id = blockId(e, p, report);
            return id == null ? null : new Choice(id, 1);
        }
        JsonObject o = e.getAsJsonObject();
        Json.checkKnownKeys(o, p, report, BLOCK, WEIGHT);
        String id = Json.id(o, BLOCK, null, p, report);
        if (id == null && !Json.has(o, BLOCK)) {
            report.error(p + ".block is required");
        }
        double weight = Json.number(o, WEIGHT, 1.0, p, report);
        if (weight <= 0) {
            report.error(p + ".weight must be greater than 0");
            return null;
        }
        return id == null ? null : new Choice(id, weight);
    }

    private static String blockId(JsonElement e, String p, ValidationReport report) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            report.error(p + " must be a block id or {\"block\": ..., \"weight\": ...}");
            return null;
        }
        String id = e.getAsString();
        if (!Json.isValidId(id)) {
            report.error(p + ": \"" + id + "\" is not a valid resource id");
            return null;
        }
        return Json.normalizeId(id);
    }

    @Override
    public String toString() {
        return this.blocks.size() == 1 ? this.blocks.getFirst().block() : this.method.name().toLowerCase(Locale.ROOT) + this.blocks;
    }
}
