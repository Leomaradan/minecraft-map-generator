package com.leomaradan.mapgen.core;

import com.google.gson.JsonElement;
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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads and validates a map pack ZIP. */
public final class MapPackReader {
    public static final String CONFIG_FILE = "map.json";
    /** Length of the hex id derived from the ZIP SHA-1. */
    private static final int ID_LENGTH = 16;

    /** Outcome of a read: {@code pack} is null when {@code report} has errors. */
    public record Result(MapPack pack, ValidationReport report) {
        public boolean ok() {
            return this.pack != null;
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
                MapPack pack = read(zip, id, file, report);
                return new Result(report.hasErrors() ? null : pack, report);
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

    private static MapPack read(Path zipPath, String id, ZipFile zip, ValidationReport report) throws IOException {
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
        MapConfig config = MapConfig.parse(configJson.getAsJsonObject(), report);
        MapConfig.Files files = config.files();
        String name = configJson.getAsJsonObject().has("name") && configJson.getAsJsonObject().get("name").isJsonPrimitive()
                ? configJson.getAsJsonObject().get("name").getAsString()
                : stripExtension(zipPath.getFileName().toString());

        // Heightmap (required, under its default name or the one given in map.json)
        if (!entries.containsKey(files.heightmap())) {
            report.error(files.explicitHeightmap()
                    ? missing(files.heightmap(), "heightmap")
                    : "the ZIP has no heightmap: add " + files.heightmap() + ", or name your heightmap file in map.json with "
                    + "\"files\": {\"heightmap\": \"<file>.png\"}");
            return null;
        }
        PngImage heightImage = readPng(zip, entries, files.heightmap(), report);
        if (heightImage == null) {
            return null;
        }
        GrayRaster heightmap = GrayRaster.of(heightImage, files.heightmap(), report);
        if (heightmap == null) {
            return null;
        }
        int bitDepth = heightmap.bitDepth();

        // Water and lava: same size and same bit depth as the heightmap.
        GrayRaster water = readFluid(zip, entries, files.water(), "water", files.explicitWater(), heightmap, report);
        GrayRaster lava = readFluid(zip, entries, files.lava(), "lava", files.explicitLava(), heightmap, report);

        // Biomes
        BiomeMapping biomeMapping = BiomeMapping.EMPTY;
        BiomeRaster biomes = null;
        boolean hasBiomeMap = files.explicitBiomes() || entries.containsKey(files.biomes());
        boolean hasBiomeMapping = files.explicitBiomeMapping() || entries.containsKey(files.biomeMapping());
        if (hasBiomeMapping) {
            if (!entries.containsKey(files.biomeMapping())) {
                report.error(missing(files.biomeMapping(), "biome_mapping"));
            } else {
                JsonElement mappingJson = readJson(zip, entries.get(files.biomeMapping()), files.biomeMapping(), report);
                if (mappingJson != null && mappingJson.isJsonObject()) {
                    biomeMapping = BiomeMapping.parse(mappingJson.getAsJsonObject(), files.biomeMapping(), report);
                } else if (mappingJson != null) {
                    report.error(files.biomeMapping() + " must be a JSON object");
                }
            }
        }
        if (hasBiomeMap) {
            PngImage biomeImage = null;
            if (!entries.containsKey(files.biomes())) {
                report.error(missing(files.biomes(), "biomes"));
            } else {
                biomeImage = readPng(zip, entries, files.biomes(), report);
            }
            if (biomeImage != null) {
                if (biomeImage.width() != heightmap.width() || biomeImage.height() != heightmap.height()) {
                    report.error(files.biomes() + " is " + biomeImage.width() + "x" + biomeImage.height() + " but the heightmap is "
                            + heightmap.width() + "x" + heightmap.height() + "; they must have the same size");
                } else if (!hasBiomeMapping) {
                    report.error(files.biomes() + " needs a biome mapping file (" + files.biomeMapping() + ")");
                } else if (!biomeMapping.entries().isEmpty()) {
                    biomes = BiomeRaster.build(biomeImage, biomeMapping, files.biomes(), report);
                }
            }
        } else if (hasBiomeMapping) {
            report.warn(files.biomeMapping() + " is present but there is no biome map (" + files.biomes() + "); it is ignored");
        }

        // Structures: read once the sampler exists (positions are in pixels)
        JsonElement structuresJson = null;
        if (files.explicitStructures() || entries.containsKey(files.structures())) {
            if (!entries.containsKey(files.structures())) {
                report.error(missing(files.structures(), "structures"));
            } else {
                structuresJson = readJson(zip, entries.get(files.structures()), files.structures(), report);
            }
        }

        // Embedded datapack
        Map<String, byte[]> datapack = readDatapack(zip, entries, files.datapack(), report);

        // Vertical layout
        HeightMapping heightMapping = HeightMapping.resolve(config.height(), bitDepth);
        int[] range = valueRange(heightmap);
        int lowest = heightMapping.toBlockY(range[0]);
        int highest = heightMapping.toBlockY(range[1]);
        for (GrayRaster fluid : new GrayRaster[]{water, lava}) {
            if (fluid != null) {
                int max = valueRange(fluid)[1];
                if (max > 0) {
                    highest = Math.max(highest, heightMapping.toBlockY(max));
                }
            }
        }
        if (config.outside().mode() == MapConfig.OutsideMode.FLAT) {
            lowest = Math.min(lowest, config.outside().groundLevel());
            highest = Math.max(highest, Math.max(config.outside().groundLevel(), config.outside().waterLevel()));
        }
        DimensionLayout layout = DimensionLayout.compute(config.dimension(), lowest, highest, report);

        if (report.hasErrors()) {
            return null;
        }
        TerrainSampler sampler = new TerrainSampler(config, heightmap, heightMapping, water, lava, biomes, biomeMapping);

        List<StructureEntry> structures = List.of();
        Map<String, byte[]> templates = new HashMap<>();
        if (structuresJson != null) {
            structures = StructureEntry.parseList(structuresJson, files.structures(), entries.keySet(), sampler, report);
            for (StructureEntry entry : structures) {
                if (entry instanceof StructureEntry.Template template && !templates.containsKey(template.path())) {
                    templates.put(template.path(), readBytes(zip, entries.get(template.path())));
                }
            }
        }
        if (report.hasErrors()) {
            return null;
        }
        return new MapPack(id, name, zipPath, config, heightmap, heightMapping, biomes, biomeMapping, water, lava, structures,
                Map.copyOf(templates), datapack, layout, sampler);
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

    private static int[] valueRange(GrayRaster raster) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int y = 0; y < raster.height(); y++) {
            for (int x = 0; x < raster.width(); x++) {
                int v = raster.value(x, y);
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
