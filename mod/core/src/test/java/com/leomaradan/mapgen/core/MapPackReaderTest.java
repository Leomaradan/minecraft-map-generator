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
        MapPackReader.Result result = put("map.json", "{}")
                .put("heightmap.png", heightmap8(4, 4))
                .put("biomes.png", biomes)
                .put("biomes.json", "{\"#00FF00\": \"forest\", \"0,0,255\": {\"biome\": \"mymap:blue\", \"top\": \"minecraft:sand\"}}")
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
                        [
                          {"type": "template", "path": "structures/house.nbt", "x": 1, "y": "surface", "z": 2, "rotation": "clockwise_90"},
                          {"path": "structures/house.nbt", "x": 5, "y": 70, "z": 6, "mirror": "front_back"},
                          {"type": "structure", "id": "minecraft:bastion_remnant", "x": 500, "z": -500}
                        ]
                        """)
                .read();
        assertTrue(result.ok(), result.report().toString());
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
