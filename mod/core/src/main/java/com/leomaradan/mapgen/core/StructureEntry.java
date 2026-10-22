package com.leomaradan.mapgen.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One entry of {@code structures.json}. */
public sealed interface StructureEntry {

    int x();

    int z();

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
    }

    /** A registered structure (vanilla, modded or from the ZIP datapack), e.g. {@code minecraft:bastion_remnant}. */
    record Structure(String id, int x, int z) implements StructureEntry {
    }

    static List<StructureEntry> parseList(JsonElement root, String fileName, Set<String> zipEntries, ValidationReport report) {
        List<StructureEntry> result = new ArrayList<>();
        JsonArray array;
        String arrayPath;
        if (root.isJsonArray()) {
            report.warn(fileName + " is a JSON array; this form is deprecated, use {\"structures\": [...]}");
            array = root.getAsJsonArray();
            arrayPath = fileName;
        } else if (root.isJsonObject()) {
            Json.checkKnownKeys(root.getAsJsonObject(), fileName, report, "$schema", "structures");
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
                result.add(entry);
            }
        }
        return List.copyOf(result);
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
            Json.checkKnownKeys(o, p, report, "type", "path", "x", "y", "z", "y_offset", "rotation", "mirror", "entities");
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
        if ("structure".equals(type)) {
            Json.checkKnownKeys(o, p, report, "type", "id", "x", "z");
            String id = Json.id(o, "id", null, p, report);
            if (id == null) {
                report.error(p + ".id is required for a structure");
                return null;
            }
            return new Structure(id, x, z);
        }
        report.error(p + ".type must be \"template\" or \"structure\"");
        return null;
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
