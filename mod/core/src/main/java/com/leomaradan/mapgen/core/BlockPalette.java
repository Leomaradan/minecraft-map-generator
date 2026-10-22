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
        double total = 0;
        for (Choice c : this.blocks) {
            total += c.weight();
        }
        this.cumulative = new double[this.blocks.size()];
        double sum = 0;
        for (int i = 0; i < this.blocks.size(); i++) {
            sum += this.blocks.get(i).weight();
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
        double u = this.method == Method.PERLIN ? this.noise.uniform(x / this.scale, y / this.scale, z / this.scale) : this.random(x, y, z);
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
            return choices == null ? null : new BlockPalette(choices, Method.RANDOM, DEFAULT_SCALE, seed);
        }
        if (!e.isJsonObject()) {
            report.error(p + " must be a block id, a list of blocks, or {\"blocks\": [...], \"method\": ...}");
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        Json.checkKnownKeys(o, p, report, "blocks", "method", "scale");
        JsonArray array = Json.array(o, "blocks", p, report);
        if (array == null) {
            if (!Json.has(o, "blocks")) {
                report.error(p + ".blocks is required");
            }
            return null;
        }
        List<Choice> choices = choices(array, p + ".blocks", report);
        String methodName = Json.string(o, "method", "random", p, report).toLowerCase(Locale.ROOT);
        Method method = switch (methodName) {
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
        if (choices == null || method == null) {
            return null;
        }
        return new BlockPalette(choices, method, scale, seed);
    }

    private static List<Choice> choices(JsonArray array, String p, ValidationReport report) {
        if (array.isEmpty()) {
            report.error(p + " must not be empty");
            return null;
        }
        List<Choice> choices = new ArrayList<>();
        boolean valid = true;
        for (int i = 0; i < array.size(); i++) {
            String ip = p + "[" + i + "]";
            JsonElement e = array.get(i);
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                Json.checkKnownKeys(o, ip, report, "block", "weight");
                String id = Json.id(o, "block", null, ip, report);
                if (id == null && !Json.has(o, "block")) {
                    report.error(ip + ".block is required");
                }
                double weight = Json.number(o, "weight", 1.0, ip, report);
                if (weight <= 0) {
                    report.error(ip + ".weight must be greater than 0");
                    id = null;
                }
                if (id == null) {
                    valid = false;
                } else {
                    choices.add(new Choice(id, weight));
                }
            } else {
                String id = blockId(e, ip, report);
                if (id == null) {
                    valid = false;
                } else {
                    choices.add(new Choice(id, 1));
                }
            }
        }
        return valid ? choices : null;
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
