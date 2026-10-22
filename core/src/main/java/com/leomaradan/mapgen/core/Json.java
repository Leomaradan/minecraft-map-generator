package com.leomaradan.mapgen.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Small helpers to read optional, typed JSON fields while reporting precise errors. */
final class Json {
    private static final Pattern RESOURCE_ID = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");

    private Json() {
    }

    static boolean isValidId(String id) {
        return RESOURCE_ID.matcher(id).matches();
    }

    /** Adds the default namespace if missing. */
    static String normalizeId(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    static void checkKnownKeys(JsonObject object, String path, ValidationReport report, String... known) {
        Set<String> knownKeys = Set.of(known);
        for (String key : object.keySet()) {
            if (!knownKeys.contains(key)) {
                report.warn(path + ": unknown key \"" + key + "\" is ignored");
            }
        }
    }

    static JsonObject object(JsonObject parent, String key, String path, ValidationReport report) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonObject()) {
            report.error(path + "." + key + " must be an object");
            return null;
        }
        return e.getAsJsonObject();
    }

    static JsonArray array(JsonObject parent, String key, String path, ValidationReport report) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonArray()) {
            report.error(path + "." + key + " must be an array");
            return null;
        }
        return e.getAsJsonArray();
    }

    static String string(JsonObject parent, String key, String def, String path, ValidationReport report) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            report.error(path + "." + key + " must be a string");
            return def;
        }
        return e.getAsString();
    }

    static String id(JsonObject parent, String key, String def, String path, ValidationReport report) {
        String value = string(parent, key, def, path, report);
        if (value == null) {
            return null;
        }
        if (!isValidId(value)) {
            report.error(path + "." + key + ": \"" + value + "\" is not a valid resource id");
            return def;
        }
        return normalizeId(value);
    }

    static boolean bool(JsonObject parent, String key, boolean def, String path, ValidationReport report) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            report.error(path + "." + key + " must be true or false");
            return def;
        }
        return e.getAsBoolean();
    }

    static boolean has(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return e != null && !e.isJsonNull();
    }

    static Integer integer(JsonObject parent, String key, Integer def, String path, ValidationReport report) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        Integer value = asInt(e);
        if (value == null) {
            report.error(path + "." + key + " must be an integer");
            return def;
        }
        return value;
    }

    static Integer asInt(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double d = e.getAsDouble();
        if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            return null;
        }
        return (int) d;
    }

    static Double number(JsonObject parent, String key, Double def, String path, ValidationReport report) {
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            report.error(path + "." + key + " must be a number");
            return def;
        }
        double d = e.getAsDouble();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            report.error(path + "." + key + " must be a finite number");
            return def;
        }
        return d;
    }

    /** Reads a list of resource ids; {@code null} when the key is absent. */
    static List<String> idList(JsonObject parent, String key, String path, ValidationReport report) {
        JsonArray array = array(parent, key, path, report);
        if (array == null) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement e = array.get(i);
            if (e.isJsonPrimitive() && ((JsonPrimitive) e).isString() && isValidId(e.getAsString())) {
                result.add(normalizeId(e.getAsString()));
            } else {
                report.error(path + "." + key + "[" + i + "] must be a resource id string");
            }
        }
        return List.copyOf(result);
    }
}
