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
        assertTrue(anyContains(noHeightmap.report().errors(), "heightmap.png is not in the ZIP"));
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
        assertEquals("minecraft:sand", pack.biomeMapping().entries().get(1).top());
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
                          "structures": [
                            {"type": "template", "path": "structures/house.nbt", "x": 1, "y": "surface", "z": 2, "rotation": "clockwise_90"},
                            {"path": "structures/house.nbt", "x": 5, "y": 70, "z": 6, "mirror": "front_back"},
                            {"type": "structure", "id": "minecraft:bastion_remnant", "x": 500, "z": -500}
                          ]
                        }
                        """)
                .read();
        assertTrue(result.ok(), result.report().toString());
        assertTrue(result.report().warnings().isEmpty(), result.report().toString());
        MapPack pack = result.pack();
        assertEquals(3, pack.structures().size());
        StructureEntry.Template first = (StructureEntry.Template) pack.structures().get(0);
        assertNull(first.y());
        assertEquals(StructureEntry.Rotation.CLOCKWISE_90, first.rotation());
        StructureEntry.Template second = (StructureEntry.Template) pack.structures().get(1);
        assertEquals(70, second.y());
        assertEquals(StructureEntry.Mirror.FRONT_BACK, second.mirror());
        assertEquals(new StructureEntry.Structure("minecraft:bastion_remnant", 500, -500), pack.structures().get(2));
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
                          {"type": "structure", "id": "minecraft:igloo"}
                        ]
                        """)
                .read();
        assertFalse(result.ok());
        assertTrue(anyContains(result.report().errors(), "missing.nbt\" is not in the ZIP"));
        assertTrue(anyContains(result.report().errors(), "not a valid resource id"));
        assertTrue(anyContains(result.report().errors(), "x and z are required"));
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
        assertEquals("minecraft:gravel", mapping.slots().get(3).top());
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
