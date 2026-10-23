package com.leomaradan.mapgen.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapPackReaderTest {
    @TempDir
    Path tmp;

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    private MapPackReaderTest put(String name, String text) {
        this.files.put(name, text.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    private MapPackReaderTest put(String name, byte[] data) {
        this.files.put(name, data);
        return this;
    }

    private MapPackReader.Result read() throws IOException {
        Path zip = this.tmp.resolve("test-map.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> e : this.files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return MapPackReader.read(zip);
    }

    private static byte[] heightmap8(int w, int h) {
        return TestPng.gray(w, h, 8, (x, y, c) -> 60 + x);
    }

    private static boolean anyContains(java.util.List<String> messages, String text) {
        return messages.stream().anyMatch(m -> m.contains(text));
    }

    @Test
    void readsMinimalPack() throws IOException {
        MapPackReader.Result result = put("map.json", "{}").put("heightmap.png", heightmap8(16, 8)).read();
        assertTrue(result.ok(), result.report().toString());
        MapPack pack = result.pack();
        assertEquals(16, pack.width());
        assertEquals(8, pack.height());
        assertEquals("test-map", pack.name());
        assertEquals(16, pack.id().length());
        assertEquals(DimensionLayout.VANILLA, pack.layout());
        assertNull(pack.biomes());
        assertEquals(java.util.List.of("minecraft:plains"), pack.biomeIds());
        // Default 8-bit mapping: value == Y. Centered origin: pixel 0 is at X=-8.
        assertEquals(60, pack.sampler().terrainY(-8, -4));
        assertEquals(75, pack.sampler().terrainY(7, 3));
    }

    @Test
    void requiresMapJsonAndHeightmap() throws IOException {
        MapPackReader.Result noConfig = put("heightmap.png", heightmap8(4, 4)).read();
        assertFalse(noConfig.ok());
        assertTrue(anyContains(noConfig.report().errors(), "map.json"));

        this.files.clear();
        MapPackReader.Result noHeightmap = put("map.json", "{}").read();
        assertFalse(noHeightmap.ok());
        assertTrue(anyContains(noHeightmap.report().errors(), "the ZIP has no heightmap: add heightmap.png"));
    }

    @Test
    void readsImagesUnderCustomNames() throws IOException {
        MapPackReader.Result result = put("map.json", """
                {"files": {"heightmap": "maps/terrain.png", "water": "/maps\\\\sea.png", "lava": "maps/magma.png",
                           "biomes": "maps/regions.png", "biome_mapping": "maps/regions.json"}}
                """)
                .put("maps/terrain.png", heightmap8(4, 4))
                .put("maps/sea.png", TestPng.gray(4, 4, 8, (x, y, c) -> x == 0 ? 80 : 0))
                .put("maps/magma.png", TestPng.gray(4, 4, 8, (x, y, c) -> x == 3 ? 90 : 0))
                .put("maps/regions.png", TestPng.rgb(4, 4, (x, y, c) -> c == 1 ? 255 : 0))
                .put("maps/regions.json", "{\"#00FF00\": \"forest\"}")
                .read();
        assertTrue(result.ok(), result.report().toString());
        MapPack pack = result.pack();
        assertNotNull(pack.water());
        assertNotNull(pack.lava());
        assertNotNull(pack.biomes());
        assertEquals("maps/sea.png", pack.config().files().water());
        TerrainSampler.Column column = pack.sampler().sample(-2, 0, new TerrainSampler.Column());
        assertEquals(80, column.waterY);
    }

    @Test
    void defaultNamesAreIgnoredWhenOtherNamesAreGiven() throws IOException {
        // water.png is not read when map.json names another water map
        MapPackReader.Result result = put("map.json", "{\"files\": {\"heightmap\": \"terrain.png\", \"water\": \"sea.png\"}}")
                .put("terrain.png", heightmap8(4, 4))
                .put("sea.png", TestPng.gray(4, 4, 8, (x, y, c) -> 0))
                .put("water.png", TestPng.gray(4, 4, 16, (x, y, c) -> 0))
                .read();
        assertTrue(result.ok(), result.report().toString());
    }

    @Test
    void reportsMissingCustomNamedFiles() throws IOException {
        MapPackReader.Result noHeightmap = put("map.json", "{\"files\": {\"heightmap\": \"terrain.png\"}}")
                .put("heightmap.png", heightmap8(4, 4))
                .read();
        assertFalse(noHeightmap.ok());
        assertTrue(anyContains(noHeightmap.report().errors(), "terrain.png is not in the ZIP (named by \"files\": {\"heightmap\"}"),
                noHeightmap.report().toString());

        this.files.clear();
        MapPackReader.Result missing = put("map.json", "{\"files\": {\"water\": \"sea.png\", \"lava\": \"magma.png\", \"biomes\": \"regions.png\"}}")
                .put("heightmap.png", heightmap8(4, 4))
                .read();
        assertFalse(missing.ok());
        assertTrue(anyContains(missing.report().errors(), "sea.png is not in the ZIP"));
        assertTrue(anyContains(missing.report().errors(), "magma.png is not in the ZIP"));
        assertTrue(anyContains(missing.report().errors(), "regions.png is not in the ZIP"));
    }

    @Test
    void rejectsEmptyFileNames() throws IOException {
        MapPackReader.Result result = put("map.json", "{\"files\": {\"heightmap\": \"\"}}").put("heightmap.png", heightmap8(4, 4)).read();
        assertFalse(result.ok());
        assertTrue(anyContains(result.report().errors(), "map.json.files.heightmap must be a file path"));
    }

    @Test
    void readsDimensionParameter() throws IOException {
        MapPackReader.Result result = put("map.json", "{\"dimension_parameter\": {\"min_y\": -128, \"height\": 512}}")
                .put("heightmap.png", heightmap8(4, 4))
                .read();
        assertTrue(result.ok(), result.report().toString());
        assertEquals(new DimensionLayout(-128, 512), result.pack().layout());

        this.files.clear();
        MapPackReader.Result invalid = put("map.json", "{\"dimension_parameter\": {\"min_y\": -60}}").put("heightmap.png", heightmap8(4, 4)).read();
        assertFalse(invalid.ok());
        assertTrue(anyContains(invalid.report().errors(), "map.json.dimension_parameter.min_y must be a multiple of 16"));

        this.files.clear();
        MapPackReader.Result old = put("map.json", "{\"dimension\": {\"min_y\": -128}}").put("heightmap.png", heightmap8(4, 4)).read();
        assertFalse(old.ok());
        assertTrue(anyContains(old.report().errors(), "the world height is set with \"dimension_parameter\""));
    }

    @Test
    void defaultsToOverworld() throws IOException {
        MapPackReader.Result result = put("map.json", "{}").put("heightmap.png", heightmap8(4, 4)).read();
        assertTrue(result.ok(), result.report().toString());
        assertEquals(MapConfig.DimensionTarget.DEFAULT, result.pack().dimension());
        assertEquals(1, result.archive().dimensions().size());
        assertSame(result.pack(), result.archive().get(""));
        assertSame(result.pack(), result.archive().get("minecraft:overworld"));
        assertSame(result.pack(), result.archive().overworld());
    }

    @Test
    void readsDimensionId() throws IOException {
        MapPackReader.Result result = put("map.json", "{\"dimension\": \"the_nether\", \"spawn\": {\"x\": 1, \"z\": 1}}")
                .put("heightmap.png", heightmap8(4, 4)).read();
        assertTrue(result.ok(), result.report().toString());
        MapConfig.DimensionTarget target = result.pack().dimension();
        assertEquals("minecraft:the_nether", target.id());
        assertFalse(target.custom());
        assertTrue(target.sizedToMap());
        assertNull(result.archive().overworld());
        // The Nether starts at Y 0, 256 blocks high.
        assertEquals(DimensionLayout.NETHER_END, result.pack().layout());
        assertTrue(result.archive().summary().startsWith("minecraft:the_nether, 4x4 px"), result.archive().summary());
        assertTrue(anyContains(result.report().warnings(), "spawn is only used in the overworld"));

        this.files.clear();
        // max_y counts from the Nether's bottom (Y 0), not the overworld's.
        MapPackReader.Result top = put("map.json", "{\"dimension\": \"minecraft:the_nether\", \"dimension_parameter\": {\"max_y\": 320}}")
                .put("heightmap.png", heightmap8(4, 4)).read();
        assertTrue(top.ok(), top.report().toString());
        assertEquals(new DimensionLayout(0, 320), top.pack().layout());
        MapArchive nether = result.archive();
        assertThrows(IllegalArgumentException.class, () -> nether.get("minecraft:overworld"));

        this.files.clear();
        MapPackReader.Result mod = put("map.json", "{\"dimension\": \"twilightforest:twilight_forest\"}").put("heightmap.png", heightmap8(4, 4)).read();
        assertTrue(mod.ok(), mod.report().toString());
        assertFalse(mod.pack().dimension().isVanilla());
        assertFalse(mod.pack().dimension().sizedToMap());
    }

    @Test
    void readsCustomDimension() throws IOException {
        MapPackReader.Result result = put("map.json", "{\"dimension\": {\"name\": \"Sky Islands\"}}").put("heightmap.png", heightmap8(4, 4)).read();
        assertTrue(result.ok(), result.report().toString());
        MapConfig.DimensionTarget target = result.pack().dimension();
        assertEquals("mapgen:sky_islands", target.id());
        assertTrue(target.custom());
        assertTrue(target.sizedToMap());
        assertNull(target.type());

        this.files.clear();
        MapPackReader.Result typed = put("map.json", "{\"dimension\": {\"name\": \"mymap:caves\", \"parameters\": {\"type\": \"the_nether\"}}}")
                .put("heightmap.png", heightmap8(4, 4)).read();
        assertTrue(typed.ok(), typed.report().toString());
        assertEquals("mymap:caves", typed.pack().dimension().id());
        assertEquals("minecraft:the_nether", typed.pack().dimension().typeId());
        assertFalse(typed.pack().dimension().sizedToMap());

        this.files.clear();
        MapPackReader.Result inline = put("map.json", "{\"dimension\": {\"name\": \"dark\", \"parameters\": {\"type\": {\"has_skylight\": false}}}}")
                .put("heightmap.png", heightmap8(4, 4)).read();
        assertTrue(inline.ok(), inline.report().toString());
        assertTrue(inline.pack().dimension().type().isJsonObject());
        assertNull(inline.pack().dimension().typeId());
        assertTrue(inline.pack().dimension().sizedToMap());
        assertFalse(inline.pack().dimension().typeSetsHeight());

        this.files.clear();
        MapPackReader.Result fixed = put("map.json", "{\"dimension\": {\"name\": \"low\", \"parameters\": {\"type\": {\"height\": 128}}}}")
                .put("heightmap.png", heightmap8(4, 4)).read();
        assertTrue(fixed.ok(), fixed.report().toString());
        assertTrue(fixed.pack().dimension().typeSetsHeight());
    }

    @Test
    void reportsBadDimensions() throws IOException {
        String[][] cases = {
                {"\"Not Valid\"", "is not a valid dimension id"},
                {"42", "must be a dimension id or"},
                {"{\"parameters\": {}}", ".name is required"},
                {"{\"name\": \"minecraft:the_end\"}", "minecraft:the_end already exists; use"},
                {"{\"name\": \"Bad:Name\"}", "is not a valid dimension id"},
                {"{\"name\": \"x\", \"parameters\": {\"generator\": {}}}", "generator can't be set"},
                {"{\"name\": \"x\", \"parameters\": {\"type\": 3}}", "type must be a dimension type id or"},
        };
        for (String[] c : cases) {
            this.files.clear();
            MapPackReader.Result result = put("map.json", "{\"dimension\": " + c[0] + "}").put("heightmap.png", heightmap8(4, 4)).read();
            assertFalse(result.ok(), c[0]);
            assertTrue(anyContains(result.report().errors(), c[1]), c[0] + ": " + result.report());
        }
    }

    @Test
    void readsDimensionsFormat() throws IOException {
        MapPackReader.Result result = put("map.json", """
                        {
                          "$schema": "x",
                          "dimensions": [
                            {"name": "Island", "dimension": "minecraft:overworld"},
                            {"dimension": "minecraft:the_nether", "files": {"heightmap": "nether/heightmap.png", "datapack": "nether_pack/"}},
                            {"dimension": {"name": "sky"}, "files": {"heightmap": "sky.png"}, "sea_level": 0}
                          ]
                        }
                        """)
                .put("heightmap.png", heightmap8(4, 4))
                .put("nether/heightmap.png", heightmap8(8, 8))
                .put("sky.png", heightmap8(2, 2))
                .put("datapack/pack.mcmeta", "{}")
                .put("datapack/data/a/x.json", "{}")
                .put("nether_pack/pack.mcmeta", "{}")
                .put("nether_pack/data/b/y.json", "{}")
                .read();
        assertTrue(result.ok(), result.report().toString());
        MapArchive archive = result.archive();
        assertEquals("Island", archive.name());
        assertEquals(3, archive.dimensions().size());
        assertEquals(8, archive.get("minecraft:the_nether").width());
        assertEquals(2, archive.get("mapgen:sky").width());
        assertEquals(0, archive.get("mapgen:sky").config().seaLevel());
        assertSame(archive.dimensions().getFirst(), archive.overworld());
        // The datapack folders of all maps are merged, and shared by every map.
        assertTrue(archive.datapack().containsKey("data/a/x.json"));
        assertTrue(archive.datapack().containsKey("data/b/y.json"));
        assertSame(archive.datapack(), archive.get("mapgen:sky").datapack());
        assertTrue(archive.summary().startsWith("3 dimensions"));
    }

    @Test
    void nullFilesAreAbsent() throws IOException {
        MapPackReader.Result result = put("map.json", """
                        {"dimensions": [
                          {"dimension": "minecraft:overworld"},
                          {"dimension": "minecraft:the_end", "files": {"heightmap": "end.png", "water": null, "biomes": null, "datapack": null}}
                        ]}
                        """)
                .put("heightmap.png", heightmap8(4, 4))
                .put("water.png", heightmap8(4, 4))
                .put("end.png", heightmap8(2, 2))
                .read();
        assertTrue(result.ok(), result.report().toString());
        MapPack end = result.archive().get("minecraft:the_end");
        assertNull(end.water());
        assertNotNull(result.archive().overworld().water());
        assertEquals(2, end.width());

        this.files.clear();
        MapPackReader.Result invalid = put("map.json", "{\"files\": {\"heightmap\": null}}").put("heightmap.png", heightmap8(4, 4)).read();
        assertFalse(invalid.ok());
        assertTrue(anyContains(invalid.report().errors(), "heightmap can't be null"));
    }

    @Test
    void reportsBadDimensionsFormat() throws IOException {
        MapPackReader.Result result = put("map.json", """
                        {"dimensions": [
                          {"dimension": "minecraft:overworld"},
                          {"dimension": "overworld", "files": {"heightmap": "missing.png"}},
                          {"sea_level": 10},
                          3,
                          {"dimension": null}
                        ], "name": "x"}
                        """)
                .put("heightmap.png", heightmap8(4, 4))
                .read();
        assertFalse(result.ok());
        assertTrue(anyContains(result.report().errors(), "map.json.dimensions[2].dimension is required"));
        assertTrue(anyContains(result.report().errors(), "map.json.dimensions[3] must be an object"));
        assertTrue(anyContains(result.report().errors(), "map.json.dimensions[4].dimension is required"));
        assertTrue(anyContains(result.report().warnings(), "unknown key \"name\""));

        this.files.clear();
        MapPackReader.Result duplicate = put("map.json", """
                        {"dimensions": [
                          {"dimension": "minecraft:overworld"},
                          {"dimension": "overworld", "files": {"heightmap": "missing.png"}}
                        ]}
                        """)
                .put("heightmap.png", heightmap8(4, 4))
                .read();
        assertFalse(duplicate.ok());
        assertTrue(anyContains(duplicate.report().errors(), "dimensions[1]: another map already generates minecraft:overworld"));
        assertTrue(anyContains(duplicate.report().errors(), "dimensions[1] (minecraft:overworld): missing.png is not in the ZIP"));

        this.files.clear();
        MapPackReader.Result empty = put("map.json", "{\"dimensions\": []}").read();
        assertFalse(empty.ok());
        assertTrue(anyContains(empty.report().errors(), "must be a non-empty array"));
    }

    @Test
    void reportsInvalidJson() throws IOException {
        MapPackReader.Result result = put("map.json", "{ \"sea_level\": ").put("heightmap.png", heightmap8(4, 4)).read();
        assertFalse(result.ok());
        assertTrue(anyContains(result.report().errors(), "not valid JSON"));
    }

    @Test
    void stripsSingleTopLevelFolder() throws IOException {
        MapPackReader.Result result = put("MyMap/map.json", "{}").put("MyMap/heightmap.png", heightmap8(4, 4)).read();
        assertTrue(result.ok(), result.report().toString());
    }

    @Test
    void rejectsFluidMapWithDifferentBitDepth() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(8, 8))
                .put("water.png", TestPng.gray(8, 8, 16, (x, y, c) -> 1000))
                .read();
        assertFalse(result.ok());
        assertTrue(anyContains(result.report().errors(), "must all use the same bit depth"), result.report().toString());
    }

    @Test
    void rejectsLavaMapWithDifferentSize() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(8, 8))
                .put("lava.png", TestPng.gray(4, 8, 8, (x, y, c) -> 0))
                .read();
        assertFalse(result.ok());
        assertTrue(anyContains(result.report().errors(), "same size"));
    }

    @Test
    void reads16BitMapsWithSameBitDepth() throws IOException {
        MapPackReader.Result result = put("map.json", "{\"height\": {\"min_y\": -64, \"max_y\": 1000}}")
                .put("heightmap.png", TestPng.gray(4, 4, 16, (x, y, c) -> x == 3 ? 65535 : 0))
                .put("water.png", TestPng.gray(4, 4, 16, (x, y, c) -> x == 0 ? 6553 : 0))
                .read();
        assertTrue(result.ok(), result.report().toString());
        MapPack pack = result.pack();
        assertEquals(16, pack.heightmap().bitDepth());
        TerrainSampler sampler = pack.sampler();
        assertEquals(1000, sampler.terrainY(1, 0));
        assertEquals(-64, sampler.terrainY(-2, 0));
        TerrainSampler.Column column = sampler.sample(-2, 0, new TerrainSampler.Column());
        // 6553/65535 of the [-64, 1000] range is ~42.4 -> 42
        assertEquals(42, column.waterY);
        assertEquals(42, column.fluidTop());
        // The world grows to fit the terrain plus headroom.
        assertTrue(pack.layout().maxY() >= 1000 + DimensionLayout.HEADROOM, pack.layout().toString());
        assertTrue(pack.layout().minY() <= -64 - DimensionLayout.FOOTROOM);
        assertEquals(0, pack.layout().height() % 16);
    }

    @Test
    void mapsBiomesWithNearestColorFallback() throws IOException {
        byte[] biomes = TestPng.rgb(4, 4, (x, y, c) -> {
            int rgb = x < 2 ? 0x00FF00 : 0x0000F0; // 0x0000F0 is not mapped, nearest is blue
            return (rgb >> (16 - 8 * c)) & 0xFF;
        });
        MapPackReader.Result result = put("map.json", "{\"biome_transition\": 0}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("biomes.png", biomes)
                .put("biomes.json", "{\"$schema\": \"x\", \"#00FF00\": \"forest\", \"0,0,255\": {\"biome\": \"mymap:blue\", \"top\": \"minecraft:sand\"}}")
                .read();
        assertTrue(result.ok(), result.report().toString());
        assertTrue(anyContains(result.report().warnings(), "#0000F0"));
        MapPack pack = result.pack();
        assertEquals(java.util.List.of("minecraft:forest", "mymap:blue", "minecraft:plains"), pack.biomeIds());
        assertEquals(0, pack.sampler().biomeIndex(-2, 0));
        assertEquals(1, pack.sampler().biomeIndex(1, 0));
        assertEquals(-1, pack.sampler().biomeIndex(100, 0));
        assertEquals(java.util.List.of("minecraft:sand"), pack.biomeMapping().slots().get(1).top().ids());
    }

    @Test
    void biomeMapNeedsMapping() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("biomes.png", TestPng.rgb(4, 4, (x, y, c) -> 0))
                .read();
        assertFalse(result.ok());
        assertTrue(anyContains(result.report().errors(), "biome mapping"));
    }

    @Test
    void readsStructures() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("structures/house.nbt", new byte[]{1, 2, 3})
                .put("structures.json", """
                        {
                          "$schema": "https://example.com/structures.schema.json",
                          "coordinates": "world",
                          "structures": [
                            {"type": "template", "path": "structures/house.nbt", "x": 1, "y": "surface", "z": 2, "rotation": "clockwise_90"},
                            {"path": "structures/house.nbt", "x": 5, "y": 70, "z": 6, "mirror": "front_back"},
                            {"type": "structure", "id": "minecraft:bastion_remnant", "x": 500, "z": -500},
                            {"type": "structure", "id": "minecraft:mansion", "x": 0, "z": 0, "retries": 0}
                          ]
                        }
                        """)
                .read();
        assertTrue(result.ok(), result.report().toString());
        assertTrue(result.report().warnings().isEmpty(), result.report().toString());
        MapPack pack = result.pack();
        assertEquals(4, pack.structures().size());
        assertEquals(StructureEntry.Structure.DEFAULT_RETRIES, ((StructureEntry.Structure) pack.structures().get(2)).retries());
        assertEquals(new StructureEntry.Structure("minecraft:mansion", null, 0, 0, 0), pack.structures().get(3));
        StructureEntry.Template first = (StructureEntry.Template) pack.structures().get(0);
        assertNull(first.y());
        assertEquals(StructureEntry.Rotation.CLOCKWISE_90, first.rotation());
        StructureEntry.Template second = (StructureEntry.Template) pack.structures().get(1);
        assertEquals(70, second.y());
        assertEquals(StructureEntry.Mirror.FRONT_BACK, second.mirror());
        assertEquals(new StructureEntry.Structure("minecraft:bastion_remnant", null, 500, -500), pack.structures().get(2));
        assertNotNull(pack.templates().get("structures/house.nbt"));
    }

    @Test
    void reportsBadStructures() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("structures.json", """
                        [
                          {"type": "template", "path": "missing.nbt", "x": 1, "z": 2},
                          {"type": "structure", "id": "Not Valid", "x": 1, "z": 2},
                          {"type": "template", "path": "a.nbt", "x": 1, "z": 2, "rotation": "sideways"},
                          {"type": "structure", "id": "minecraft:igloo"},
                          {"type": "structure", "id": "minecraft:igloo", "x": 1, "z": 2, "retries": 100},
                          {"type": "structure", "id": "minecraft:igloo", "x": 1, "z": 2, "retries": -1},
                          {"type": "castle", "id": "minecraft:igloo", "x": 1, "z": 2},
                          {"type": "template", "x": 1, "z": 2},
                          {"type": "template", "path": "/structures/a.txt", "x": 1, "z": 2}
                        ]
                        """)
                .read();
        assertFalse(result.ok());
        assertTrue(anyContains(result.report().errors(), "retries must be between 0 and 64"));
        assertTrue(anyContains(result.report().errors(), "missing.nbt\" is not in the ZIP"));
        assertTrue(anyContains(result.report().errors(), "not a valid resource id"));
        assertTrue(anyContains(result.report().errors(), "x and z are required"));
        assertTrue(anyContains(result.report().errors(), "type must be \"template\" or \"structure\""));
        assertTrue(anyContains(result.report().errors(), ".path is required for a template"));
        assertTrue(anyContains(result.report().errors(), "must point to a structure template (.nbt file)"));
        assertEquals(2, result.report().errors().stream().filter(e -> e.contains("retries must be between")).count());
    }

    @Test
    void legacyStructureArrayIsDeprecated() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("structures.json", "[{\"id\": \"minecraft:igloo\", \"x\": 1, \"z\": 2}]")
                .read();
        assertTrue(result.ok(), result.report().toString());
        assertEquals(1, result.pack().structures().size());
        assertTrue(anyContains(result.report().warnings(), "deprecated"));
    }

    @Test
    void structuresObjectNeedsList() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("structures.json", "{\"$schema\": \"x\"}")
                .read();
        assertFalse(result.ok());
        assertTrue(anyContains(result.report().errors(), "structures.json.structures is required"));
    }

    @Test
    void structurePositionsAreHeightmapPixels() throws IOException {
        // 16 x 8 pixels, 4 blocks per pixel, centered: pixel 0 starts at X=-32, Z=-16.
        MapPackReader.Result result = put("map.json", "{\"horizontal_scale\": 4}")
                .put("heightmap.png", heightmap8(16, 8))
                .put("structures.json", """
                        {"structures": [
                          {"id": "minecraft:village_desert", "fallback_id": "minecraft:village_plains", "x": 0, "z": 0},
                          {"id": "mymod:castle", "x": 10, "z": 5},
                          {"id": "minecraft:igloo", "x": 40, "z": 2}
                        ]}
                        """)
                .read();
        assertTrue(result.ok(), result.report().toString());
        java.util.List<StructureEntry> structures = result.pack().structures();
        assertEquals(new StructureEntry.Structure("minecraft:village_desert", "minecraft:village_plains", -30, -14), structures.get(0));
        assertEquals(-32 + 42, structures.get(1).x(), "center of pixel 10");
        assertEquals(-16 + 22, structures.get(1).z());
        assertTrue(anyContains(result.report().warnings(), "structures[2]: pixel 40, 2 is outside the heightmap"), result.report().toString());

        MapPackReader.Result bad = put("structures.json", "{\"coordinates\": \"blocks\", \"structures\": []}").read();
        assertFalse(bad.ok());
        assertTrue(anyContains(bad.report().errors(), "coordinates must be \"pixels\" or \"world\""));
    }

    @Test
    void structureFallback() {
        StructureEntry.Structure modded = new StructureEntry.Structure("mymod:castle", "minecraft:pillager_outpost", 0, 0);
        assertEquals("mymod:castle", modded.resolve(id -> true));
        assertEquals("minecraft:pillager_outpost", modded.resolve(id -> id.startsWith("minecraft:")));
        assertEquals(null, modded.resolve(id -> false));
        assertEquals(null, new StructureEntry.Structure("mymod:castle", null, 0, 0).resolve(id -> id.startsWith("minecraft:")));
        assertEquals(new StructureEntry.Structure("mymod:castle", "minecraft:pillager_outpost", 5, 6), modded.at(5, 6));
    }

    @Test
    void structuresMustBeAnObject() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("structures.json", "\"castle\"")
                .read();
        assertFalse(result.ok());
        assertTrue(anyContains(result.report().errors(), "structures.json must be a JSON object"));
    }

    @Test
    void readsSubstituteBiomes() throws IOException {
        // Left half forest, right half plains; height 60 + x; water on the column x=0.
        byte[] biomes = TestPng.rgb(8, 8, (x, y, c) -> {
            int rgb = x < 4 ? 0x00FF00 : 0xFFFF00;
            return (rgb >> (16 - 8 * c)) & 0xFF;
        });
        MapPackReader.Result result = put("map.json", "{\"origin\": [0, 0], \"biome_transition\": 0, \"$schema\": \"x\"}")
                .put("heightmap.png", heightmap8(8, 8))
                .put("water.png", TestPng.gray(8, 8, 8, (x, y, c) -> x == 0 ? 70 : 0))
                .put("biomes.png", biomes)
                .put("biomes.json", """
                        {
                          "#00FF00": {"biome": "forest", "biome_river": "river", "river_width": 1, "biome_border": {"biome": "mymap:edge", "top": "minecraft:gravel"},
                                      "border_width": 2, "biome_underground": "lush_caves", "underground_depth": 10},
                          "#FFFF00": {"biome": "plains", "biome_mountain": "stony_peaks", "mountain_y": 66}
                        }
                        """)
                .read();
        assertTrue(result.ok(), result.report().toString());
        MapPack pack = result.pack();
        BiomeMapping mapping = pack.biomeMapping();
        assertEquals(java.util.List.of("minecraft:forest", "minecraft:plains", "minecraft:river", "mymap:edge", "minecraft:lush_caves",
                "minecraft:stony_peaks"), mapping.slots().stream().map(BiomeMapping.Variant::biome).toList());
        assertEquals(java.util.List.of("minecraft:gravel"), mapping.slots().get(3).top().ids());
        assertEquals("#00FF00.biome_border", mapping.slots().get(3).origin());

        TerrainSampler s = pack.sampler();
        assertEquals(2, s.biomeIndex(0, 0), "water: river");
        assertEquals(2, s.biomeIndex(1, 0), "1 block from the water: river bank");
        assertEquals(3, s.biomeIndex(2, 0), "2 blocks from the plains: border");
        assertEquals(3, s.biomeIndex(3, 0));
        assertEquals(1, s.biomeIndex(4, 0), "plains below Y=66");
        assertEquals(1, s.biomeIndex(5, 0));
        assertEquals(5, s.biomeIndex(6, 0), "plains at Y=66: mountain");
        // Ground of x=1 is Y=61: underground from Y=51 down.
        assertEquals(4, s.biomeIndex(1, 51, 0));
        assertEquals(2, s.biomeIndex(1, 52, 0));
        assertEquals(1, s.biomeIndex(5, -40, 0), "no underground biome for plains");
    }

    @Test
    void riverBanksDefaultToFourBlocks() throws IOException {
        // Water on the column x=0 of a 12-pixel wide forest.
        MapPackReader.Result result = put("map.json", "{\"origin\": [0, 0], \"biome_transition\": 0}")
                .put("heightmap.png", heightmap8(12, 4))
                .put("water.png", TestPng.gray(12, 4, 8, (x, y, c) -> x == 0 ? 80 : 0))
                .put("biomes.png", TestPng.rgb(12, 4, (x, y, c) -> c == 1 ? 0xFF : 0))
                .put("biomes.json", "{\"#00FF00\": {\"biome\": \"forest\", \"biome_river\": \"river\"}}")
                .read();
        assertTrue(result.ok(), result.report().toString());
        TerrainSampler s = result.pack().sampler();
        for (int x = 0; x <= 4; x++) {
            assertEquals(1, s.biomeIndex(x, 2), "x=" + x);
        }
        assertEquals(0, s.biomeIndex(5, 2));
        assertEquals(0, s.biomeIndex(11, 2));
    }

    @Test
    void biomeTransitionShiftsBordersWithinItsWidth() throws IOException {
        byte[] biomes = TestPng.rgb(64, 64, (x, y, c) -> {
            int rgb = x < 32 ? 0x00FF00 : 0xFFFF00;
            return (rgb >> (16 - 8 * c)) & 0xFF;
        });
        MapPackReader.Result result = put("map.json", "{\"origin\": [0, 0], \"biome_transition\": 6}")
                .put("heightmap.png", heightmap8(64, 64))
                .put("biomes.png", biomes)
                .put("biomes.json", "{\"#00FF00\": \"forest\", \"#FFFF00\": \"plains\"}")
                .read();
        assertTrue(result.ok(), result.report().toString());
        TerrainSampler s = result.pack().sampler();
        boolean shifted = false;
        for (int z = 0; z < 64; z++) {
            assertEquals(0, s.biomeIndex(32 - 7, z));
            assertEquals(1, s.biomeIndex(32 + 6, z));
            shifted |= s.biomeIndex(30, z) == 1 || s.biomeIndex(33, z) == 0;
        }
        assertTrue(shifted, "the border should not be a straight line");
    }

    @Test
    void substituteErrors() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("biomes.png", TestPng.rgb(4, 4, (x, y, c) -> 0))
                .put("biomes.json", """
                        {"#000000": {"biome": "forest", "biome_underground": {"biome": "lush_caves"}, "border_width": 0, "mountain_y": 100}}
                        """)
                .read();
        assertFalse(result.ok());
        assertTrue(anyContains(result.report().errors(), "biome_underground must be a biome id"));
        assertTrue(anyContains(result.report().errors(), "border_width must be between 1 and 64"));
        assertTrue(anyContains(result.report().warnings(), "mountain_y has no effect without biome_mountain"));
    }

    @Test
    void moreSubstituteErrors() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("biomes.png", TestPng.rgb(4, 4, (x, y, c) -> 0))
                .put("biomes.json", """
                        {
                          "#000000": {"biome": "forest", "biome_river": "Not Valid", "river_width": 65, "biome_border": {"top": "minecraft:sand"},
                                      "biome_mountain": 3, "biome_underground": "lush_caves", "underground_depth": -1},
                          "0,0,0": "plains",
                          "#FFFFFF": {"top": "minecraft:sand"},
                          "#FF0000": 12
                        }
                        """)
                .read();
        assertFalse(result.ok());
        java.util.List<String> errors = result.report().errors();
        assertTrue(anyContains(errors, "biome_river: \"Not Valid\" is not a valid biome id"), errors.toString());
        assertTrue(anyContains(errors, "river_width must be between 0 and 64"));
        assertTrue(anyContains(errors, "biome_border.biome is required"));
        assertTrue(anyContains(errors, "biome_mountain must be a biome id or an object"));
        assertTrue(anyContains(errors, "underground_depth must not be negative"));
        assertTrue(anyContains(errors, "#000000 is mapped twice"));
        assertTrue(anyContains(errors, "[\"#FFFFFF\"].biome is required"));
        assertTrue(anyContains(errors, "[\"#FF0000\"] must be a biome id or an object"));
    }

    @Test
    void cappedDistancesAreFarAway() throws IOException {
        // 0.1 block per pixel: distances stop at 255 pixels (25.5 blocks), less than the widths.
        // Forest up to pixel 1100, plains after; water on the column x=0.
        MapPackReader.Result result = put("map.json", "{\"origin\": [0, 0], \"horizontal_scale\": 0.1, \"biome_transition\": 0}")
                .put("heightmap.png", TestPng.gray(1200, 2, 8, (x, y, c) -> 60))
                .put("water.png", TestPng.gray(1200, 2, 8, (x, y, c) -> x == 0 ? 80 : 0))
                .put("biomes.png", TestPng.rgb(1200, 2, (x, y, c) -> c == 1 || (c == 0 && x >= 1100) ? 0xFF : 0))
                .put("biomes.json", """
                        {"#00FF00": {"biome": "forest", "biome_river": "river", "river_width": 30, "biome_border": "birch_forest", "border_width": 40},
                         "#FFFF00": "plains"}
                        """)
                .read();
        assertTrue(result.ok(), result.report().toString());
        TerrainSampler s = result.pack().sampler();
        assertEquals(2, s.biomeIndex(20, 0), "200 pixels from the water: river bank");
        assertEquals(0, s.biomeIndex(60, 0), "far from the water and the plains");
        assertEquals(3, s.biomeIndex(90, 0), "200 pixels from the plains: border");
        assertEquals(1, s.biomeIndex(115, 0));
    }

    @Test
    void decorationAndBlocksPerBiome() throws IOException {
        MapPackReader.Result result = put("map.json", "{\"blocks\": {\"default\": [\"stone\", \"andesite\"]}}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("biomes.png", TestPng.rgb(4, 4, (x, y, c) -> 0))
                .put("biomes.json", """
                        {"#000000": {"biome": "forest", "top": {"blocks": ["grass_block", {"block": "podzol", "weight": 2}], "method": "perlin"},
                                     "filler": "dirt", "features": {"disabled": ["trees_plains"]}, "carvers": {"canyon": false},
                                     "biome_mountain": {"biome": "grove", "features": false, "carvers": false, "underwater": ["stone", "dirt"]}}}
                        """)
                .read();
        assertTrue(result.ok(), result.report().toString());
        MapPack pack = result.pack();
        BiomeMapping mapping = pack.biomeMapping();
        BiomeMapping.Variant forest = mapping.slots().get(0);
        assertEquals(BlockPalette.Method.PERLIN, forest.top().method());
        assertFalse(forest.features().allows("minecraft:trees_plains", "vegetal_decoration"));
        assertEquals(new MapConfig.Carvers(true, false, true), forest.carvers());
        BiomeMapping.Variant grove = mapping.slots().get(1);
        assertEquals(MapConfig.Features.NONE, grove.features());
        assertEquals(MapConfig.Carvers.NONE, grove.carvers());
        assertTrue(mapping.hasFeatureOverrides());
        assertTrue(mapping.hasCarverOverrides());
        assertEquals(java.util.List.of("minecraft:stone", "minecraft:andesite", "minecraft:grass_block", "minecraft:podzol", "minecraft:dirt"),
                pack.blockIds());
    }

    @Test
    void noDecorationOverridesByDefault() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("biomes.png", TestPng.rgb(4, 4, (x, y, c) -> 0))
                .put("biomes.json", "{\"#000000\": \"forest\"}")
                .read();
        assertTrue(result.ok(), result.report().toString());
        assertFalse(result.pack().biomeMapping().hasFeatureOverrides());
        assertFalse(result.pack().biomeMapping().hasCarverOverrides());
    }

    @Test
    void substitutesInheritDecorationOfTheirEntry() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("biomes.png", TestPng.rgb(4, 4, (x, y, c) -> 0))
                .put("biomes.json", """
                        {"#000000": {"biome": "old_growth_spruce_taiga", "snow": false, "carvers": false, "features": {"disabled": ["forest_rock"]},
                                     "biome_border": "old_growth_pine_taiga", "biome_underground": "lush_caves",
                                     "biome_mountain": {"biome": "grove", "snow": true}}}
                        """)
                .read();
        assertTrue(result.ok(), result.report().toString());
        java.util.List<BiomeMapping.Variant> slots = result.pack().biomeMapping().slots();
        BiomeMapping.Variant border = slots.stream().filter(v -> v.biome().equals("minecraft:old_growth_pine_taiga")).findFirst().orElseThrow();
        assertEquals(MapConfig.Snow.NONE, border.snow(), "plain-id substitute: snow of its entry");
        assertEquals(MapConfig.Carvers.NONE, border.carvers());
        assertFalse(border.features().allows("minecraft:forest_rock", "local_modifications"));
        assertEquals(MapConfig.Snow.NONE, slots.stream().filter(v -> v.biome().equals("minecraft:lush_caves")).findFirst().orElseThrow().snow());
        BiomeMapping.Variant mountain = slots.stream().filter(v -> v.biome().equals("minecraft:grove")).findFirst().orElseThrow();
        assertEquals(MapConfig.Snow.ALL, mountain.snow(), "own snow wins");
        assertEquals(MapConfig.Carvers.NONE, mountain.carvers(), "the rest is inherited");
    }

    @Test
    void biomeTransitionReachesItsWidthAndKeepsTwoPixelLinesConnected() throws IOException {
        // Forest with a 2-pixel plains line on the columns x=64-65 (a river drawn in the biome map).
        MapPackReader.Result result = put("map.json", "{\"origin\": [0, 0], \"biome_transition\": 16}")
                .put("heightmap.png", heightmap8(128, 256))
                .put("biomes.png", TestPng.rgb(128, 256, (x, y, c) -> {
                    int rgb = x == 64 || x == 65 ? 0xFFFF00 : 0x00FF00;
                    return (rgb >> (16 - 8 * c)) & 0xFF;
                }))
                .put("biomes.json", "{\"#00FF00\": \"forest\", \"#FFFF00\": \"plains\"}")
                .read();
        assertTrue(result.ok(), result.report().toString());
        TerrainSampler s = result.pack().sampler();
        int maxShift = 0;
        for (int z = 0; z < 256; z++) {
            boolean found = false;
            for (int x = 40; x < 90; x++) {
                if (s.biomeIndex(x, z) == 1) {
                    maxShift = Math.max(maxShift, Math.abs(x - 64));
                    found = true;
                }
            }
            assertTrue(found, "the line crosses z=" + z);
            assertTrue(maxShift <= 16, "shift " + maxShift + " at z=" + z);
        }
        assertTrue(maxShift >= 12, "the shift should reach most of biome_transition, got " + maxShift);
    }

    @Test
    void snowPerBiome() throws IOException {
        // Columns 0-3: taiga with no snow below Y=100, mountain (from Y=62) snowy anywhere; columns 4-7: plains, map default.
        byte[] biomes = TestPng.rgb(8, 4, (x, y, c) -> {
            int rgb = x < 4 ? 0x00FF00 : 0xFFFF00;
            return (rgb >> (16 - 8 * c)) & 0xFF;
        });
        MapPackReader.Result result = put("map.json", "{\"origin\": [0, 0], \"biome_transition\": 0, \"snow\": false}")
                .put("heightmap.png", heightmap8(8, 4))
                .put("biomes.png", biomes)
                .put("biomes.json", """
                        {"#00FF00": {"biome": "taiga", "snow": {"min_y": 100}, "biome_mountain": {"biome": "grove", "snow": true}, "mountain_y": 62},
                         "#FFFF00": "plains"}
                        """)
                .read();
        assertTrue(result.ok(), result.report().toString());
        TerrainSampler s = result.pack().sampler();
        assertFalse(s.allowsSnow(0, 99, 0), "taiga below min_y");
        assertTrue(s.allowsSnow(0, 100, 0));
        assertTrue(s.allowsSnow(2, 0, 0), "mountain: snow everywhere");
        assertFalse(s.allowsSnow(5, 500, 0), "plains: snow of map.json");
        assertFalse(s.allowsSnow(-10, 500, 0), "outside: snow of map.json");
    }

    @Test
    void snowEverywhereByDefault() throws IOException {
        TerrainSampler s = put("map.json", "{}").put("heightmap.png", heightmap8(4, 4)).read().pack().sampler();
        assertTrue(s.allowsSnow(0, 1000, 0));
    }

    @Test
    void riversNeedABiomeMap() throws IOException {
        MapPackReader.Result result = put("map.json", "{\"origin\": [0, 0]}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("water.png", TestPng.gray(4, 4, 8, (x, y, c) -> x == 0 ? 80 : 0))
                .put("biomes.json", "{\"#00FF00\": {\"biome\": \"forest\", \"biome_river\": \"river\"}}")
                .read();
        assertTrue(result.ok(), result.report().toString());
        assertEquals(-1, result.pack().sampler().biomeIndex(0, 0));
    }

    @Test
    void readsDatapack() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("datapack/pack.mcmeta", "{\"pack\": {\"description\": \"x\"}}")
                .put("datapack/data/mymap/worldgen/biome/blue.json", "{}")
                .read();
        assertTrue(result.ok(), result.report().toString());
        assertTrue(result.pack().hasDatapack());
        assertTrue(result.pack().datapack().containsKey("data/mymap/worldgen/biome/blue.json"));
    }

    @Test
    void datapackNeedsPackMcmeta() throws IOException {
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("datapack/data/mymap/worldgen/biome/blue.json", "{}")
                .read();
        assertFalse(result.ok());
        assertTrue(anyContains(result.report().errors(), "pack.mcmeta"));
    }

    @Test
    void rejectsUnsafePaths() {
        assertFalse(MapPackReader.isSafePath("../evil.txt"));
        assertFalse(MapPackReader.isSafePath("datapack/../../evil.txt"));
        assertFalse(MapPackReader.isSafePath("C:/evil.txt"));
        assertTrue(MapPackReader.isSafePath("datapack/data/x/y.json"));
    }

    @Test
    void sameContentGivesSameId() throws IOException {
        put("map.json", "{}").put("heightmap.png", heightmap8(4, 4));
        String first = read().pack().id();
        String second = read().pack().id();
        assertEquals(first, second);
        put("map.json", "{\"sea_level\": 10}");
        assertFalse(first.equals(read().pack().id()));
    }

    @Test
    void warnsOnUnknownKeys() throws IOException {
        MapPackReader.Result result = put("map.json", "{\"sealevel\": 10}").put("heightmap.png", heightmap8(4, 4)).read();
        assertTrue(result.ok());
        assertTrue(anyContains(result.report().warnings(), "sealevel"));
    }
}
