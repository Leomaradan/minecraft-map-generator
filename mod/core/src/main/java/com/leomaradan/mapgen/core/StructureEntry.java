package com.leomaradan.mapgen.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/** One entry of {@code structures.json}. Positions are world coordinates once the file is read. */
public sealed interface StructureEntry {

    int x();

    int z();

    /** The same entry at another position. */
    StructureEntry at(int x, int z);

    enum Rotation {
        NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90
    }

    enum Mirror {
        NONE, LEFT_RIGHT, FRONT_BACK
    }

    /**
     * A structure template (vanilla {@code .nbt} file) stored in the ZIP.
     *
     * @param y       fixed Y of the template origin, or null to put it on the surface
     * @param yOffset added to the surface Y when {@code y} is null (e.g. -1 to sink the floor)
     */
    record Template(String path, int x, Integer y, int yOffset, int z, Rotation rotation, Mirror mirror, boolean includeEntities)
            implements StructureEntry {
        @Override
        public Template at(int x, int z) {
            return new Template(this.path, x, this.y, this.yOffset, z, this.rotation, this.mirror, this.includeEntities);
        }
    }

    /**
     * A registered structure (vanilla, modded or from the ZIP datapack), e.g. {@code minecraft:bastion_remnant}.
     *
     * @param fallbackId structure used when {@code id} doesn't exist (e.g. from a mod that isn't installed), or null
     * @param retries    extra attempts when the structure can't generate at its position (other
     *                   seeds, then the chunks around); 0 disables them
     */
    record Structure(String id, String fallbackId, int x, int z, int retries) implements StructureEntry {
        public static final int DEFAULT_RETRIES = 32;
        public static final int MAX_RETRIES = 64;

        public Structure(String id, String fallbackId, int x, int z) {
            this(id, fallbackId, x, z, DEFAULT_RETRIES);
        }

        @Override
        public Structure at(int x, int z) {
            return new Structure(this.id, this.fallbackId, x, z, this.retries);
        }

        /** The id to generate: {@code id} if it exists, else {@code fallbackId} if it exists, else null (skipped). */
        public String resolve(Predicate<String> exists) {
            if (exists.test(this.id)) {
                return this.id;
            }
            return this.fallbackId != null && exists.test(this.fallbackId) ? this.fallbackId : null;
        }
    }

    /**
     * Reads structures.json. Positions are heightmap pixels, converted to world coordinates with
     * {@code sampler} (the center of the pixel), unless {@code "coordinates": "world"}; the
     * deprecated list form uses world coordinates.
     */
    static List<StructureEntry> parseList(JsonElement root, String fileName, Set<String> zipEntries, TerrainSampler sampler, ValidationReport report) {
        List<StructureEntry> result = new ArrayList<>();
        JsonArray array;
        String arrayPath;
        boolean pixels;
        if (root.isJsonArray()) {
            report.warn(fileName + " is a JSON array; this form is deprecated, use {\"structures\": [...]} (positions are then in pixels)");
            array = root.getAsJsonArray();
            arrayPath = fileName;
            pixels = false;
        } else if (root.isJsonObject()) {
            Json.checkKnownKeys(root.getAsJsonObject(), fileName, report, "$schema", "coordinates", "structures");
            pixels = pixelCoordinates(root.getAsJsonObject(), fileName, report);
            array = Json.array(root.getAsJsonObject(), "structures", fileName, report);
            arrayPath = fileName + ".structures";
            if (array == null) {
                if (!root.getAsJsonObject().has("structures")) {
                    report.error(fileName + ".structures is required");
                }
                return result;
            }
        } else {
            report.error(fileName + " must be a JSON object: {\"structures\": [...]}");
            return result;
        }
        for (int i = 0; i < array.size(); i++) {
            String p = arrayPath + "[" + i + "]";
            JsonElement e = array.get(i);
            if (!e.isJsonObject()) {
                report.error(p + " must be an object");
                continue;
            }
            StructureEntry entry = parse(e.getAsJsonObject(), p, zipEntries, report);
            if (entry != null) {
                result.add(pixels ? toWorld(entry, p, sampler, report) : entry);
            }
        }
        return List.copyOf(result);
    }

    /** {@code "coordinates"}: {@code "pixels"} (default) or {@code "world"}. */
    private static boolean pixelCoordinates(JsonObject root, String fileName, ValidationReport report) {
        String coordinates = Json.string(root, "coordinates", "pixels", fileName, report).toLowerCase(Locale.ROOT);
        if (!coordinates.equals("pixels") && !coordinates.equals("world")) {
            report.error(fileName + ".coordinates must be \"pixels\" or \"world\"");
        }
        return !coordinates.equals("world");
    }

    private static StructureEntry toWorld(StructureEntry entry, String p, TerrainSampler sampler, ValidationReport report) {
        if (!sampler.isPixelInside(entry.x(), entry.z())) {
            report.warn(p + ": pixel " + entry.x() + ", " + entry.z() + " is outside the heightmap");
        }
        return entry.at(sampler.pixelToWorldX(entry.x()), sampler.pixelToWorldZ(entry.z()));
    }

    private static StructureEntry parse(JsonObject o, String p, Set<String> zipEntries, ValidationReport report) {
        String type = Json.string(o, "type", null, p, report);
        if (type == null) {
            // Infer the type: a "path" means a template, an "id" a registered structure.
            type = o.has("path") ? "template" : o.has("id") ? "structure" : null;
        }
        Integer x = Json.integer(o, "x", null, p, report);
        Integer z = Json.integer(o, "z", null, p, report);
        if (x == null || z == null) {
            report.error(p + ": x and z are required integers");
            return null;
        }
        if ("template".equals(type)) {
            return parseTemplate(o, p, x, z, zipEntries, report);
        }
        if ("structure".equals(type)) {
            return parseStructure(o, p, x, z, report);
        }
        report.error(p + ".type must be \"template\" or \"structure\"");
        return null;
    }

    private static StructureEntry parseTemplate(JsonObject o, String p, int x, int z, Set<String> zipEntries, ValidationReport report) {
        Json.checkKnownKeys(o, p, report, "type", "path", "x", "y", "z", "y_offset", "rotation", "mirror", "entities");
        String path = templatePath(o, p, zipEntries, report);
        if (path == null) {
            return null;
        }
        Integer y = null;
        JsonElement yJson = o.get("y");
        if (yJson != null && !yJson.isJsonNull()) {
            if (yJson.isJsonPrimitive() && yJson.getAsJsonPrimitive().isString() && "surface".equalsIgnoreCase(yJson.getAsString())) {
                y = null;
            } else {
                y = Json.asInt(yJson);
                if (y == null) {
                    report.error(p + ".y must be an integer or \"surface\"");
                    return null;
                }
            }
        }
        Rotation rotation = enumValue(Rotation.class, Json.string(o, "rotation", "none", p, report), p + ".rotation", report);
        Mirror mirror = enumValue(Mirror.class, Json.string(o, "mirror", "none", p, report), p + ".mirror", report);
        if (rotation == null || mirror == null) {
            return null;
        }
        return new Template(path, x, y, Json.integer(o, "y_offset", 0, p, report), z, rotation, mirror, Json.bool(o, "entities", true, p, report));
    }

    /** The template's path in the ZIP, or null (reported) when it is missing or invalid. */
    private static String templatePath(JsonObject o, String p, Set<String> zipEntries, ValidationReport report) {
        String path = Json.string(o, "path", null, p, report);
        if (path == null) {
            report.error(p + ".path is required for a template");
            return null;
        }
        path = path.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (!path.endsWith(".nbt")) {
            report.error(p + ".path must point to a structure template (.nbt file)");
            return null;
        }
        if (!zipEntries.contains(path)) {
            report.error(p + ": \"" + path + "\" is not in the ZIP");
            return null;
        }
        return path;
    }

    private static StructureEntry parseStructure(JsonObject o, String p, int x, int z, ValidationReport report) {
        Json.checkKnownKeys(o, p, report, "type", "id", "fallback_id", "x", "z", "retries");
        String id = Json.id(o, "id", null, p, report);
        if (id == null) {
            report.error(p + ".id is required for a structure");
            return null;
        }
        int retries = Json.integer(o, "retries", Structure.DEFAULT_RETRIES, p, report);
        if (retries < 0 || retries > Structure.MAX_RETRIES) {
            report.error(p + ".retries must be between 0 and " + Structure.MAX_RETRIES);
            retries = Structure.DEFAULT_RETRIES;
        }
        return new Structure(id, Json.id(o, "fallback_id", null, p, report), x, z, retries);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name, String p, ValidationReport report) {
        try {
            return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            StringBuilder allowed = new StringBuilder();
            for (E value : type.getEnumConstants()) {
                if (!allowed.isEmpty()) {
                    allowed.append(", ");
                }
                allowed.append(value.name().toLowerCase(Locale.ROOT));
            }
            report.error(p + " must be one of " + allowed + " (got \"" + name + "\")");
            return null;
        }
    }
}
