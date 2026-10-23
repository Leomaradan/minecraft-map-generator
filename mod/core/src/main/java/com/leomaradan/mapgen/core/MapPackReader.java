package com.leomaradan.mapgen.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.leomaradan.mapgen.core.png.PngImage;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads and validates a map pack ZIP. */
public final class MapPackReader {
    public static final String CONFIG_FILE = "map.json";
    /** Length of the hex id derived from the ZIP SHA-1. */
    private static final int ID_LENGTH = 16;

    public static final String KEY_DIMENSIONS = "dimensions";

    /** Outcome of a read: {@code archive} is null when {@code report} has errors. */
    public record Result(MapArchive archive, ValidationReport report) {
        public boolean ok() {
            return this.archive != null;
        }

        /** The first map of the ZIP (the only one in the single format), or null. */
        public MapPack pack() {
            return this.archive == null ? null : this.archive.dimensions().getFirst();
        }
    }

    private MapPackReader() {
    }

    public static Result read(Path zip) {
        ValidationReport report = new ValidationReport();
        if (!Files.isRegularFile(zip)) {
            report.error(zip + " is not a file");
            return new Result(null, report);
        }
        try {
            String id = computeId(zip);
            try (ZipFile file = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
                MapArchive archive = read(zip, id, file, report);
                return new Result(report.hasErrors() ? null : archive, report);
            }
        } catch (IOException e) {
            report.error("can't read " + zip.getFileName() + ": " + e.getMessage());
            return new Result(null, report);
        }
    }

    public static String computeId(Path zip) throws IOException {
        try (InputStream in = Files.newInputStream(zip)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, ID_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MapArchive read(Path zipPath, String id, ZipFile zip, ValidationReport report) throws IOException {
        Map<String, ZipEntry> entries = listEntries(zip, report);
        if (!entries.containsKey(CONFIG_FILE)) {
            report.error("the ZIP has no " + CONFIG_FILE + " at its root");
            return null;
        }

        JsonElement configJson = readJson(zip, entries.get(CONFIG_FILE), CONFIG_FILE, report);
        if (configJson == null) {
            return null;
        }
        if (!configJson.isJsonObject()) {
            report.error(CONFIG_FILE + " must be a JSON object");
            return null;
        }
        JsonObject root = configJson.getAsJsonObject();
        String zipName = stripExtension(zipPath.getFileName().toString());
        Source source = new Source(zipPath, id, zip, entries);
        List<MapPack> packs = root.has(KEY_DIMENSIONS)
                ? readDimensions(source, root, zipName, report)
                : readMaps(source, List.of(root), zipName, report);
        if (packs.isEmpty() || report.hasErrors()) {
            return null;
        }
        return new MapArchive(id, packs.getFirst().name(), zipPath, packs);
    }

    /** The ZIP being read. */
    private record Source(Path path, String id, ZipFile zip, Map<String, ZipEntry> entries) {
    }

    /** The {@code "dimensions"} format: one map.json per dimension. */
    private static List<MapPack> readDimensions(Source source, JsonObject root, String zipName, ValidationReport report) throws IOException {
        Json.checkKnownKeys(root, CONFIG_FILE, report, "$schema", KEY_DIMENSIONS);
        JsonArray list = Json.array(root, KEY_DIMENSIONS, CONFIG_FILE, report);
        if (list == null || list.isEmpty()) {
            report.error(CONFIG_FILE + "." + KEY_DIMENSIONS + " must be a non-empty array of maps");
            return List.of();
        }
        List<JsonObject> maps = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String p = CONFIG_FILE + "." + KEY_DIMENSIONS + "[" + i + "]";
            if (!list.get(i).isJsonObject()) {
                report.error(p + " must be an object (a map.json without $schema)");
            } else if (!Json.has(list.get(i).getAsJsonObject(), "dimension")) {
                report.error(p + ".dimension is required");
            } else {
                maps.add(list.get(i).getAsJsonObject());
            }
        }
        return report.hasErrors() ? List.of() : readMaps(source, maps, zipName, report);
    }

    /** Reads the maps of a ZIP; empty when one is unusable. With several maps, messages name their dimension. */
    private static List<MapPack> readMaps(Source source, List<JsonObject> maps, String zipName, ValidationReport report) throws IOException {
        ZipFile zip = source.zip();
        Map<String, ZipEntry> entries = source.entries();
        List<ValidationReport> reports = new ArrayList<>();
        List<MapConfig> configs = new ArrayList<>();
        Map<String, byte[]> datapack = new LinkedHashMap<>();
        Set<String> folders = new HashSet<>();
        for (JsonObject json : maps) {
            ValidationReport own = maps.size() == 1 ? report : new ValidationReport();
            MapConfig config = MapConfig.parse(json, own);
            reports.add(own);
            configs.add(config);
            if (folders.add(config.files().datapack())) {
                readDatapack(zip, entries, config.files().datapack(), own).forEach(datapack::putIfAbsent);
            }
        }
        Map<String, byte[]> shared = datapack.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(datapack);
        List<MapPack> packs = new ArrayList<>();
        Set<String> dimensions = new HashSet<>();
        for (int i = 0; i < maps.size(); i++) {
            MapConfig config = configs.get(i);
            ValidationReport own = reports.get(i);
            String name = maps.get(i).has("name") && maps.get(i).get("name").isJsonPrimitive() ? maps.get(i).get("name").getAsString() : zipName;
            MapPack pack = readMap(source, config, name, shared, own);
            if (own != report) {
                report.addAll(own, KEY_DIMENSIONS + "[" + i + "] (" + config.target().id() + "): ");
            }
            if (!dimensions.add(config.target().id())) {
                report.error(KEY_DIMENSIONS + "[" + i + "]: another map already generates " + config.target().id()
                        + "; each dimension can only have one map");
            }
            packs.add(pack);
        }
        return report.hasErrors() ? List.of() : packs;
    }

    private static MapPack readMap(Source source, MapConfig config, String name, Map<String, byte[]> datapack, ValidationReport report)
            throws IOException {
        ZipFile zip = source.zip();
        Map<String, ZipEntry> entries = source.entries();
        MapConfig.Files files = config.files();
        GrayRaster heightmap = readHeightmap(zip, entries, files, report);
        if (heightmap == null) {
            return null;
        }

        // Water and lava: same size and same bit depth as the heightmap.
        GrayRaster water = readFluid(zip, entries, files.water(), MapConfig.FILE_WATER, files.explicitWater(), heightmap, report);
        GrayRaster lava = readFluid(zip, entries, files.lava(), MapConfig.FILE_LAVA, files.explicitLava(), heightmap, report);

        // Biomes
        boolean hasBiomeMapping = !files.biomes().isEmpty() && (files.explicitBiomeMapping() || entries.containsKey(files.biomeMapping()));
        BiomeMapping biomeMapping = hasBiomeMapping ? readBiomeMapping(zip, entries, files, report) : BiomeMapping.EMPTY;
        BiomeRaster biomes = readBiomes(zip, entries, files, heightmap, biomeMapping, hasBiomeMapping, report);

        // Structures: parsed once the sampler exists (positions are in pixels)
        JsonElement structuresJson = readOptionalJson(zip, entries, files.structures(), MapConfig.FILE_STRUCTURES, files.explicitStructures(),
                report);

        HeightMapping heightMapping = HeightMapping.resolve(config.height(), heightmap.bitDepth());
        int[] range = blockRange(config, heightMapping, heightmap, water, lava);
        DimensionLayout layout = DimensionLayout.compute(config.dimension(), DimensionLayout.baseline(config.target()), range[0], range[1], report);
        if (report.hasErrors()) {
            return null;
        }
        TerrainSampler sampler = new TerrainSampler(config, heightmap, heightMapping, water, lava, biomes, biomeMapping);

        List<StructureEntry> structures = structuresJson == null
                ? List.of()
                : StructureEntry.parseList(structuresJson, files.structures(), entries.keySet(), sampler, report);
        Map<String, byte[]> templates = readTemplates(zip, entries, structures);
        if (report.hasErrors()) {
            return null;
        }
        return new MapPack(source.id(), name, source.path(), config, heightmap, heightMapping, biomes, biomeMapping, water, lava, structures,
                templates, datapack, layout, sampler, range[0], range[1]);
    }

    /** The heightmap, under its default name or the one given in map.json; null (with an error) if unusable. */
    private static GrayRaster readHeightmap(ZipFile zip, Map<String, ZipEntry> entries, MapConfig.Files files, ValidationReport report)
            throws IOException {
        if (!entries.containsKey(files.heightmap())) {
            report.error(files.explicitHeightmap()
                    ? missing(files.heightmap(), MapConfig.FILE_HEIGHTMAP)
                    : "the ZIP has no heightmap: add " + files.heightmap() + ", or name your heightmap file in map.json with "
                    + "\"files\": {\"heightmap\": \"<file>.png\"}");
            return null;
        }
        PngImage image = readPng(zip, entries, files.heightmap(), report);
        return image == null ? null : GrayRaster.of(image, files.heightmap(), report);
    }

    private static BiomeMapping readBiomeMapping(ZipFile zip, Map<String, ZipEntry> entries, MapConfig.Files files, ValidationReport report)
            throws IOException {
        JsonElement json = readOptionalJson(zip, entries, files.biomeMapping(), MapConfig.FILE_BIOME_MAPPING, true, report);
        if (json == null) {
            return BiomeMapping.EMPTY;
        }
        if (!json.isJsonObject()) {
            report.error(files.biomeMapping() + " must be a JSON object");
            return BiomeMapping.EMPTY;
        }
        return BiomeMapping.parse(json.getAsJsonObject(), files.biomeMapping(), report);
    }

    /** The biome map, or null when there is none (or it is unusable, with an error). */
    private static BiomeRaster readBiomes(ZipFile zip, Map<String, ZipEntry> entries, MapConfig.Files files, GrayRaster heightmap,
                                          BiomeMapping mapping, boolean hasBiomeMapping, ValidationReport report) throws IOException {
        if (!files.explicitBiomes() && !entries.containsKey(files.biomes())) {
            if (hasBiomeMapping && !files.biomes().isEmpty()) {
                report.warn(files.biomeMapping() + " is present but there is no biome map (" + files.biomes() + "); it is ignored");
            }
            return null;
        }
        if (!entries.containsKey(files.biomes())) {
            report.error(missing(files.biomes(), MapConfig.FILE_BIOMES));
            return null;
        }
        PngImage image = readPng(zip, entries, files.biomes(), report);
        if (image == null) {
            return null;
        }
        if (image.width() != heightmap.width() || image.height() != heightmap.height()) {
            report.error(files.biomes() + " is " + image.width() + "x" + image.height() + " but the heightmap is "
                    + heightmap.width() + "x" + heightmap.height() + "; they must have the same size");
            return null;
        }
        if (!hasBiomeMapping) {
            report.error(files.biomes() + " needs a biome mapping file (" + files.biomeMapping() + ")");
            return null;
        }
        return mapping.entries().isEmpty() ? null : BiomeRaster.build(image, mapping, files.biomes(), report);
    }

    /**
     * A JSON file that is read only if present, unless {@code explicit} (named in map.json), in
     * which case it must be present.
     */
    private static JsonElement readOptionalJson(ZipFile zip, Map<String, ZipEntry> entries, String path, String key, boolean explicit,
                                                ValidationReport report) throws IOException {
        if (!entries.containsKey(path)) {
            if (explicit) {
                report.error(missing(path, key));
            }
            return null;
        }
        return readJson(zip, entries.get(path), path, report);
    }

    /** The lowest and highest block Y the map produces. */
    private static int[] blockRange(MapConfig config, HeightMapping mapping, GrayRaster heightmap, GrayRaster water, GrayRaster lava) {
        // Pixels at 0 are empty when the map floats in the void (see TerrainSampler): they are not the lowest ground.
        boolean emptyAtZero = config.outside().mode() == MapConfig.OutsideMode.VOID && !config.blocks().bedrock();
        int[] range = valueRange(heightmap, emptyAtZero);
        if (range[0] > range[1]) {
            range = new int[]{0, 0};
        }
        int lowest = mapping.toBlockY(range[0]);
        int highest = mapping.toBlockY(range[1]);
        for (GrayRaster fluid : new GrayRaster[]{water, lava}) {
            int max = fluid == null ? 0 : valueRange(fluid, false)[1];
            if (max > 0) {
                highest = Math.max(highest, mapping.toBlockY(max));
            }
        }
        if (config.outside().mode() == MapConfig.OutsideMode.FLAT) {
            lowest = Math.min(lowest, config.outside().groundLevel());
            highest = Math.max(highest, Math.max(config.outside().groundLevel(), config.outside().waterLevel()));
        }
        return new int[]{lowest, highest};
    }

    /** Raw .nbt files referenced by template structures, by ZIP path. */
    private static Map<String, byte[]> readTemplates(ZipFile zip, Map<String, ZipEntry> entries, List<StructureEntry> structures)
            throws IOException {
        Map<String, byte[]> templates = new HashMap<>();
        for (StructureEntry entry : structures) {
            if (entry instanceof StructureEntry.Template template && !templates.containsKey(template.path())) {
                templates.put(template.path(), readBytes(zip, entries.get(template.path())));
            }
        }
        return Map.copyOf(templates);
    }

    /**
     * Lists the ZIP entries by normalized path. If everything sits in a single top-level folder
     * (the user zipped the folder rather than its content), that folder is stripped.
     */
    private static Map<String, ZipEntry> listEntries(ZipFile zip, ValidationReport report) {
        Map<String, ZipEntry> raw = new LinkedHashMap<>();
        Enumeration<? extends ZipEntry> e = zip.entries();
        while (e.hasMoreElements()) {
            ZipEntry entry = e.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName().replace('\\', '/');
            while (name.startsWith("/")) {
                name = name.substring(1);
            }
            if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) {
                continue;
            }
            if (!isSafePath(name)) {
                report.error("unsafe path in the ZIP: " + entry.getName());
                continue;
            }
            raw.put(name, entry);
        }
        if (raw.containsKey(CONFIG_FILE)) {
            return raw;
        }
        String prefix = null;
        for (String name : raw.keySet()) {
            int slash = name.indexOf('/');
            String first = slash < 0 ? "" : name.substring(0, slash + 1);
            if (prefix == null) {
                prefix = first;
            } else if (!prefix.equals(first)) {
                return raw;
            }
        }
        if (prefix == null || prefix.isEmpty()) {
            return raw;
        }
        Map<String, ZipEntry> stripped = new LinkedHashMap<>();
        for (Map.Entry<String, ZipEntry> entry : raw.entrySet()) {
            stripped.put(entry.getKey().substring(prefix.length()), entry.getValue());
        }
        return stripped;
    }

    /** Rejects absolute paths and parent-directory segments (protects datapack extraction). */
    static boolean isSafePath(String name) {
        if (name.isEmpty() || name.contains(":") || name.startsWith("/")) {
            return false;
        }
        for (String segment : name.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                return false;
            }
        }
        return true;
    }

    /** Error for a file named in map.json ({@code "files": {key: path}}) but absent from the ZIP. */
    private static String missing(String path, String key) {
        return path + " is not in the ZIP (named by \"files\": {\"" + key + "\"} in " + CONFIG_FILE + ")";
    }

    private static GrayRaster readFluid(ZipFile zip, Map<String, ZipEntry> entries, String path, String key, boolean explicit,
                                        GrayRaster heightmap, ValidationReport report) throws IOException {
        if (!entries.containsKey(path)) {
            if (explicit) {
                report.error(missing(path, key));
            }
            return null;
        }
        PngImage image = readPng(zip, entries, path, report);
        if (image == null) {
            return null;
        }
        if (image.width() != heightmap.width() || image.height() != heightmap.height()) {
            report.error(path + " is " + image.width() + "x" + image.height() + " but the heightmap is " + heightmap.width() + "x"
                    + heightmap.height() + "; they must have the same size");
            return null;
        }
        int depth = GrayRaster.effectiveBitDepth(image);
        if (depth != heightmap.bitDepth()) {
            report.error(path + " is " + depth + "-bit but the heightmap is " + heightmap.bitDepth()
                    + "-bit; the heightmap, water map and lava map must all use the same bit depth");
            return null;
        }
        return GrayRaster.of(image, path, report);
    }

    /** Decodes a PNG that is known to be in the ZIP. */
    private static PngImage readPng(ZipFile zip, Map<String, ZipEntry> entries, String path, ValidationReport report) throws IOException {
        try (InputStream in = zip.getInputStream(entries.get(path))) {
            return PngImage.read(in);
        } catch (IOException e) {
            report.error(path + ": " + e.getMessage());
            return null;
        }
    }

    private static JsonElement readJson(ZipFile zip, ZipEntry entry, String path, ValidationReport report) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (JsonParseException e) {
            report.error(path + " is not valid JSON: " + rootMessage(e));
            return null;
        }
    }

    private static Map<String, byte[]> readDatapack(ZipFile zip, Map<String, ZipEntry> entries, String folder, ValidationReport report)
            throws IOException {
        if (folder.isEmpty()) {
            return Map.of();
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, ZipEntry> entry : entries.entrySet()) {
            if (entry.getKey().startsWith(folder) && entry.getKey().length() > folder.length()) {
                files.put(entry.getKey().substring(folder.length()), readBytes(zip, entry.getValue()));
            }
        }
        if (!files.isEmpty() && !files.containsKey("pack.mcmeta")) {
            report.error(folder + " contains files but no pack.mcmeta; it is not a valid datapack");
        }
        if (!files.isEmpty() && files.keySet().stream().noneMatch(f -> f.startsWith("data/"))) {
            report.warn(folder + " has no data/ folder; the datapack is empty");
        }
        return files.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(files);
    }

    private static byte[] readBytes(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /** Smallest and largest values; with {@code skipZero}, 0 is ignored (min > max when every value is 0). */
    private static int[] valueRange(GrayRaster raster, boolean skipZero) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int y = 0; y < raster.height(); y++) {
            for (int x = 0; x < raster.width(); x++) {
                int v = raster.value(x, y);
                if (skipZero && v == 0) {
                    continue;
                }
                if (v < min) {
                    min = v;
                }
                if (v > max) {
                    max = v;
                }
            }
        }
        return new int[]{min, max};
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}
