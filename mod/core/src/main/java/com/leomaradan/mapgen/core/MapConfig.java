package com.leomaradan.mapgen.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Contents of {@code map.json}, the world configuration of a map pack. Every field is optional;
 * see {@code docs/FORMAT.md} for the reference.
 */
public record MapConfig(
        int format,
        Files files,
        Integer originX,
        Integer originZ,
        double horizontalScale,
        HeightSpec height,
        Dimension dimension,
        int seaLevel,
        int biomeTransition,
        Outside outside,
        Spawn spawn,
        Blocks blocks,
        Decoration decoration,
        Snow snow,
        VanillaStructures vanillaStructures
) {
    public static final int CURRENT_FORMAT = 1;

    /** Keys of {@code files} in map.json. */
    public static final String FILE_HEIGHTMAP = "heightmap";
    public static final String FILE_BIOMES = "biomes";
    public static final String FILE_BIOME_MAPPING = "biome_mapping";
    public static final String FILE_WATER = "water";
    public static final String FILE_LAVA = "lava";
    public static final String FILE_STRUCTURES = "structures";
    private static final String KEY_HEIGHT = "height";
    /** Default {@code biome_transition}: biome borders are shifted by up to this many blocks. */
    public static final int DEFAULT_BIOME_TRANSITION = 4;
    public static final int MAX_BIOME_TRANSITION = 64;
    private static final String ENABLED = "enabled";
    private static final String DISABLED = "disabled";
    private static final String ONLY = "only";
    private static final String DEFAULT_BLOCK = "minecraft:stone";
    private static final String VANILLA_STRUCTURES = "vanilla_structures";

    /** Paths inside the ZIP. {@code explicit*} tells whether the user named the file (then it must exist). */
    public record Files(String heightmap, boolean explicitHeightmap, String biomes, boolean explicitBiomes, String biomeMapping, boolean explicitBiomeMapping,
                        String water, boolean explicitWater, String lava, boolean explicitLava,
                        String structures, boolean explicitStructures, String datapack) {
    }

    /**
     * How raster values map to Y levels: either {@code y = baseY + value * scale}, or a linear
     * mapping of the full value range onto [minY, maxY]. All null means "default for the bit depth".
     */
    public record HeightSpec(Double baseY, Double scale, Double minY, Double maxY) {
        public boolean isRange() {
            return this.minY != null;
        }
    }

    /** Explicit world height. Null fields are derived from the height mapping. */
    public record Dimension(Integer minY, Integer height) {
    }

    public enum OutsideMode {
        /** Flat ground at {@code ground_level}, water up to {@code water_level}. */
        FLAT,
        /** Nothing is generated outside the map. */
        VOID,
        /** The border pixels are extended to infinity. */
        EXTEND
    }

    /** @param border put a world border around the map when the world is created */
    public record Outside(OutsideMode mode, int groundLevel, int waterLevel, String biome, boolean border) {
    }

    public record Spawn(int x, int z, Integer y) {
    }

    public record Blocks(BlockPalette defaultBlock, boolean bedrock) {
    }

    public record Decoration(Features features, Carvers carvers) {
    }

    /** Which biome carvers run: {@code cave} (caves, nether caves) and {@code canyon} (ravines). */
    public record Carvers(boolean cave, boolean canyon) {
        public static final Carvers ALL = new Carvers(true, true);
        public static final Carvers NONE = new Carvers(false, false);

        public boolean any() {
            return this.cave || this.canyon;
        }

        /** {@code true}, {@code false} or {@code {"cave", "canyon"}}; null when absent or invalid. */
        static Carvers parse(JsonObject parent, String key, String path, ValidationReport report) {
            JsonElement e = parent.get(key);
            if (e == null || e.isJsonNull()) {
                return null;
            }
            String cp = path + "." + key;
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
                return e.getAsBoolean() ? ALL : NONE;
            }
            if (!e.isJsonObject()) {
                report.error(cp + " must be true, false or {\"cave\": ..., \"canyon\": ...}");
                return null;
            }
            JsonObject o = e.getAsJsonObject();
            Json.checkKnownKeys(o, cp, report, "cave", "canyon");
            return new Carvers(Json.bool(o, "cave", true, cp, report), Json.bool(o, "canyon", true, cp, report));
        }
    }

    /**
     * Filter for the biome features (placed features), by id or by generation step.
     *
     * @param disabledSteps generation steps to skip, e.g. {@code lakes}
     * @param only          when non-null, only these features are placed
     */
    public record Features(boolean enabled, List<String> disabled, List<String> disabledSteps, List<String> only) {
        public static final Features ALL = new Features(true, List.of(), List.of(), null);
        public static final Features NONE = new Features(false, List.of(), List.of(), null);

        /** Generation steps of Minecraft's biome features, in order. */
        public static final List<String> STEPS = List.of("raw_generation", "lakes", "local_modifications", "underground_structures",
                "surface_structures", "strongholds", "underground_ores", "underground_decoration", "fluid_springs", "vegetal_decoration",
                "top_layer_modification");

        public boolean allows(String featureId, String step) {
            if (!this.enabled || this.disabledSteps.contains(step) || this.disabled.contains(featureId)) {
                return false;
            }
            return this.only == null || this.only.contains(featureId);
        }

        /** True when no feature is filtered out individually (all or nothing). */
        public boolean isUnfiltered() {
            return this.disabled.isEmpty() && this.disabledSteps.isEmpty() && this.only == null;
        }

        /** {@code true}, {@code false}, or {@code {"enabled", "disabled", "disabled_steps", "only"}}; null when absent. */
        static Features parse(JsonObject parent, String key, String path, ValidationReport report) {
            JsonElement e = parent.get(key);
            if (e == null || e.isJsonNull()) {
                return null;
            }
            if (e.isJsonPrimitive()) {
                return Json.bool(parent, key, true, path, report) ? ALL : NONE;
            }
            JsonObject json = Json.object(parent, key, path, report);
            if (json == null) {
                return null;
            }
            String fp = path + "." + key;
            Json.checkKnownKeys(json, fp, report, ENABLED, DISABLED, "disabled_steps", ONLY);
            List<String> disabled = Json.idList(json, DISABLED, fp, report);
            return new Features(Json.bool(json, ENABLED, true, fp, report), disabled == null ? List.of() : disabled, parseSteps(json, fp, report),
                    Json.idList(json, ONLY, fp, report));
        }

        private static List<String> parseSteps(JsonObject json, String fp, ValidationReport report) {
            JsonArray stepsJson = Json.array(json, "disabled_steps", fp, report);
            if (stepsJson == null) {
                return List.of();
            }
            List<String> steps = new ArrayList<>();
            for (int i = 0; i < stepsJson.size(); i++) {
                JsonElement step = stepsJson.get(i);
                String name = step.isJsonPrimitive() && step.getAsJsonPrimitive().isString() ? step.getAsString().toLowerCase(Locale.ROOT) : null;
                if (name == null || !STEPS.contains(name)) {
                    report.error(fp + ".disabled_steps[" + i + "] must be one of " + String.join(", ", STEPS));
                } else {
                    steps.add(name);
                }
            }
            return List.copyOf(steps);
        }
    }

    /**
     * Where snow layers and ice may form. It only removes snow: the biome must still be cold enough
     * at that height (Minecraft's biomes get colder above {@code sea_level + 17}).
     *
     * @param minY lowest Y with snow, null for no limit
     * @param maxY highest Y with snow, null for no limit
     */
    public record Snow(boolean enabled, Integer minY, Integer maxY) {
        public static final Snow ALL = new Snow(true, null, null);
        public static final Snow NONE = new Snow(false, null, null);

        public boolean allows(int y) {
            return this.enabled && (this.minY == null || y >= this.minY) && (this.maxY == null || y <= this.maxY);
        }

        /** {@code true}, {@code false} or {@code {"min_y", "max_y"}}; null when absent or invalid. */
        static Snow parse(JsonObject parent, String key, String path, ValidationReport report) {
            JsonElement e = parent.get(key);
            if (e == null || e.isJsonNull()) {
                return null;
            }
            String sp = path + "." + key;
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
                return e.getAsBoolean() ? ALL : NONE;
            }
            if (!e.isJsonObject()) {
                report.error(sp + " must be true, false or {\"min_y\": ..., \"max_y\": ...}");
                return null;
            }
            JsonObject o = e.getAsJsonObject();
            Json.checkKnownKeys(o, sp, report, "min_y", "max_y");
            Integer minY = Json.integer(o, "min_y", null, sp, report);
            Integer maxY = Json.integer(o, "max_y", null, sp, report);
            if (minY != null && maxY != null && minY > maxY) {
                report.error(sp + ".min_y must not be above max_y");
                return null;
            }
            return new Snow(true, minY, maxY);
        }
    }

    /**
     * Filter for vanilla (and modded) structures placed randomly. Ids may name a structure set
     * (e.g. {@code minecraft:villages}) or a single structure (e.g. {@code minecraft:village_plains}).
     *
     * @param only when non-null, only these are allowed
     */
    public record VanillaStructures(boolean enabled, List<String> disabled, List<String> only) {
        public static final VanillaStructures ALL = new VanillaStructures(true, List.of(), null);

        public boolean allows(String structureSetId, String structureId) {
            if (!this.enabled) {
                return false;
            }
            if (this.disabled.contains(structureSetId) || this.disabled.contains(structureId)) {
                return false;
            }
            return this.only == null || this.only.contains(structureSetId) || this.only.contains(structureId);
        }
    }

    public static MapConfig parse(JsonObject root, ValidationReport report) {
        String p = "map.json";
        Json.checkKnownKeys(root, p, report, "format", "files", "origin", "horizontal_scale", KEY_HEIGHT, "dimension_parameter", "sea_level",
                "biome_transition", "outside", "spawn", "blocks", "decoration", "snow", VANILLA_STRUCTURES, "name", "description", "author", "$schema");

        int format = Json.integer(root, "format", CURRENT_FORMAT, p, report);
        if (format > CURRENT_FORMAT) {
            report.error("map.json format " + format + " is newer than supported (" + CURRENT_FORMAT + "); update the mod");
        }

        Files files = parseFiles(Json.object(root, "files", p, report), report);

        Integer originX = null;
        Integer originZ = null;
        JsonArray origin = Json.array(root, "origin", p, report);
        if (origin != null) {
            if (origin.size() == 2 && Json.asInt(origin.get(0)) != null && Json.asInt(origin.get(1)) != null) {
                originX = Json.asInt(origin.get(0));
                originZ = Json.asInt(origin.get(1));
            } else {
                report.error("map.json.origin must be [x, z] (two integers)");
            }
        }

        double scale = Json.number(root, "horizontal_scale", 1.0, p, report);
        if (scale <= 0 || scale > 1024) {
            report.error("map.json.horizontal_scale must be in ]0, 1024]");
            scale = 1.0;
        }

        HeightSpec height = parseHeight(Json.object(root, KEY_HEIGHT, p, report), report);
        Dimension dimension = parseDimension(Json.object(root, "dimension_parameter", p, report), report);
        int seaLevel = Json.integer(root, "sea_level", 63, p, report);
        int biomeTransition = Json.integer(root, "biome_transition", DEFAULT_BIOME_TRANSITION, p, report);
        if (biomeTransition < 0 || biomeTransition > MAX_BIOME_TRANSITION) {
            report.error("map.json.biome_transition must be between 0 and " + MAX_BIOME_TRANSITION);
            biomeTransition = DEFAULT_BIOME_TRANSITION;
        }
        Outside outside = parseOutside(Json.object(root, "outside", p, report), report);
        Spawn spawn = parseSpawn(Json.object(root, "spawn", p, report), report);

        Blocks blocks = parseBlocks(Json.object(root, "blocks", p, report), report);
        Decoration decoration = parseDecoration(Json.object(root, "decoration", p, report), report);
        Snow snow = Snow.parse(root, "snow", p, report);
        VanillaStructures vanilla = parseVanillaStructures(root, report);

        return new MapConfig(format, files, originX, originZ, scale, height, dimension, seaLevel, biomeTransition, outside, spawn, blocks, decoration,
                snow != null ? snow : Snow.ALL, vanilla);
    }

    private static Blocks parseBlocks(JsonObject json, ValidationReport report) {
        if (json == null) {
            return new Blocks(BlockPalette.of(DEFAULT_BLOCK), true);
        }
        String bp = "map.json.blocks";
        Json.checkKnownKeys(json, bp, report, "default", "bedrock");
        BlockPalette defaultBlock = BlockPalette.parse(json, "default", bp, report);
        return new Blocks(defaultBlock != null ? defaultBlock : BlockPalette.of(DEFAULT_BLOCK), Json.bool(json, "bedrock", true, bp, report));
    }

    private static Decoration parseDecoration(JsonObject json, ValidationReport report) {
        if (json == null) {
            return new Decoration(Features.ALL, Carvers.ALL);
        }
        String dp = "map.json.decoration";
        Json.checkKnownKeys(json, dp, report, "features", "carvers");
        Features features = Features.parse(json, "features", dp, report);
        Carvers carvers = Carvers.parse(json, "carvers", dp, report);
        return new Decoration(features != null ? features : Features.ALL, carvers != null ? carvers : Carvers.ALL);
    }

    private static VanillaStructures parseVanillaStructures(JsonObject root, ValidationReport report) {
        String p = "map.json";
        if (root.has(VANILLA_STRUCTURES) && root.get(VANILLA_STRUCTURES).isJsonPrimitive()) {
            // Shorthand: "vanilla_structures": false
            return new VanillaStructures(Json.bool(root, VANILLA_STRUCTURES, true, p, report), List.of(), null);
        }
        JsonObject json = Json.object(root, VANILLA_STRUCTURES, p, report);
        if (json == null) {
            return VanillaStructures.ALL;
        }
        String vp = p + "." + VANILLA_STRUCTURES;
        Json.checkKnownKeys(json, vp, report, ENABLED, DISABLED, ONLY);
        List<String> disabled = Json.idList(json, DISABLED, vp, report);
        return new VanillaStructures(Json.bool(json, ENABLED, true, vp, report), disabled == null ? List.of() : disabled,
                Json.idList(json, ONLY, vp, report));
    }

    private static Files parseFiles(JsonObject json, ValidationReport report) {
        if (json == null) {
            json = new JsonObject();
        }
        String p = "map.json.files";
        Json.checkKnownKeys(json, p, report, FILE_HEIGHTMAP, FILE_BIOMES, FILE_BIOME_MAPPING, FILE_WATER, FILE_LAVA, FILE_STRUCTURES, "datapack");
        return new Files(
                file(json, FILE_HEIGHTMAP, "heightmap.png", report), Json.has(json, FILE_HEIGHTMAP),
                file(json, FILE_BIOMES, "biomes.png", report), Json.has(json, FILE_BIOMES),
                file(json, FILE_BIOME_MAPPING, "biomes.json", report), Json.has(json, FILE_BIOME_MAPPING),
                file(json, FILE_WATER, "water.png", report), Json.has(json, FILE_WATER),
                file(json, FILE_LAVA, "lava.png", report), Json.has(json, FILE_LAVA),
                file(json, FILE_STRUCTURES, "structures.json", report), Json.has(json, FILE_STRUCTURES),
                normalizeFolder(Json.string(json, "datapack", "datapack/", p, report)));
    }

    /** A file path inside the ZIP, with "/" separators and no leading slash. */
    private static String file(JsonObject json, String key, String def, ValidationReport report) {
        String path = Json.string(json, key, def, "map.json.files", report).replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty() || path.endsWith("/")) {
            report.error("map.json.files." + key + " must be a file path inside the ZIP");
            return def;
        }
        return path;
    }

    private static String normalizeFolder(String folder) {
        String f = folder.replace('\\', '/');
        while (f.startsWith("/")) {
            f = f.substring(1);
        }
        return f.isEmpty() || f.endsWith("/") ? f : f + "/";
    }

    private static HeightSpec parseHeight(JsonObject json, ValidationReport report) {
        if (json == null) {
            return new HeightSpec(null, null, null, null);
        }
        String p = "map.json.height";
        Json.checkKnownKeys(json, p, report, "base_y", "scale", "min_y", "max_y");
        Double baseY = Json.number(json, "base_y", null, p, report);
        Double scale = Json.number(json, "scale", null, p, report);
        Double minY = Json.number(json, "min_y", null, p, report);
        Double maxY = Json.number(json, "max_y", null, p, report);
        boolean linear = baseY != null || scale != null;
        boolean range = minY != null || maxY != null;
        if (linear && range) {
            report.error(p + ": use either {base_y, scale} or {min_y, max_y}, not both");
            return new HeightSpec(null, null, null, null);
        }
        if (range) {
            if (minY == null || maxY == null) {
                report.error(p + ": min_y and max_y must both be set");
                return new HeightSpec(null, null, null, null);
            }
            if (maxY <= minY) {
                report.error(p + ": max_y must be greater than min_y");
                return new HeightSpec(null, null, null, null);
            }
            return new HeightSpec(null, null, minY, maxY);
        }
        if (scale != null && scale <= 0) {
            report.error(p + ".scale must be positive");
            scale = null;
        }
        if (baseY == null && scale == null) {
            return new HeightSpec(null, null, null, null);
        }
        return new HeightSpec(baseY == null ? 0.0 : baseY, scale == null ? 1.0 : scale, null, null);
    }

    private static Dimension parseDimension(JsonObject json, ValidationReport report) {
        if (json == null) {
            return new Dimension(null, null);
        }
        String p = "map.json.dimension_parameter";
        Json.checkKnownKeys(json, p, report, "min_y", KEY_HEIGHT, "max_y");
        Integer minY = Json.integer(json, "min_y", null, p, report);
        Integer height = Json.integer(json, KEY_HEIGHT, null, p, report);
        Integer maxY = Json.integer(json, "max_y", null, p, report);
        if (maxY != null) {
            if (height != null) {
                report.error(p + ": use either height or max_y, not both");
            } else {
                int base = minY != null ? minY : DimensionLayout.VANILLA_MIN_Y;
                height = maxY - base;
            }
        }
        return new Dimension(minY, height);
    }

    private static Outside parseOutside(JsonObject json, ValidationReport report) {
        if (json == null) {
            return new Outside(OutsideMode.FLAT, 64, 63, "minecraft:plains", true);
        }
        String p = "map.json.outside";
        Json.checkKnownKeys(json, p, report, "mode", "ground_level", "water_level", "biome", "border");
        String modeName = Json.string(json, "mode", "flat", p, report);
        OutsideMode mode;
        try {
            mode = OutsideMode.valueOf(modeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            report.error(p + ".mode must be one of flat, void, extend (got \"" + modeName + "\")");
            mode = OutsideMode.FLAT;
        }
        return new Outside(mode,
                Json.integer(json, "ground_level", 64, p, report),
                Json.integer(json, "water_level", 63, p, report),
                Json.id(json, "biome", "minecraft:plains", p, report),
                Json.bool(json, "border", true, p, report));
    }

    private static Spawn parseSpawn(JsonObject json, ValidationReport report) {
        if (json == null) {
            return null;
        }
        String p = "map.json.spawn";
        Json.checkKnownKeys(json, p, report, "x", "y", "z");
        Integer x = Json.integer(json, "x", null, p, report);
        Integer z = Json.integer(json, "z", null, p, report);
        if (x == null || z == null) {
            report.error(p + ": x and z are required");
            return null;
        }
        return new Spawn(x, z, Json.integer(json, "y", null, p, report));
    }
}
