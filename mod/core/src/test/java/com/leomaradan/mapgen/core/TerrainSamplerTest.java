package com.leomaradan.mapgen.core;

import com.google.gson.JsonParser;
import com.leomaradan.mapgen.core.png.PngImage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainSamplerTest {

    private static GrayRaster raster(int w, int h, TestPng.Samples samples) throws IOException {
        PngImage image = PngImage.read(new ByteArrayInputStream(TestPng.gray(w, h, 8, samples)));
        return GrayRaster.of(image, "test.png", new ValidationReport());
    }

    private static MapConfig config(String json) {
        ValidationReport report = new ValidationReport();
        MapConfig config = MapConfig.parse(JsonParser.parseString(json).getAsJsonObject(), report);
        assertFalse(report.hasErrors(), report.toString());
        return config;
    }

    private static TerrainSampler sampler(String json, GrayRaster height, GrayRaster water, GrayRaster lava) {
        MapConfig config = config(json);
        return new TerrainSampler(config, height, HeightMapping.resolve(config.height(), height.bitDepth()), water, lava, null, BiomeMapping.EMPTY);
    }

    @Test
    void explicitOriginAndFlatOutside() throws IOException {
        TerrainSampler s = sampler("{\"origin\": [100, 200], \"outside\": {\"ground_level\": 10, \"water_level\": 20}}",
                raster(4, 4, (x, y, c) -> 50 + x + 10 * y), null, null);
        assertEquals(50, s.terrainY(100, 200));
        assertEquals(83, s.terrainY(103, 203));
        assertTrue(s.isInside(103, 203));
        assertFalse(s.isInside(104, 200));
        TerrainSampler.Column outside = s.sample(99, 200, new TerrainSampler.Column());
        assertFalse(outside.inside);
        assertEquals(10, outside.terrainY);
        assertEquals(20, outside.waterY);
        assertEquals(20, outside.fluidTop());
        assertEquals(-1, outside.biome);
    }

    @Test
    void voidAndExtendOutside() throws IOException {
        GrayRaster height = raster(4, 4, (x, y, c) -> 50 + x);
        TerrainSampler voidSampler = sampler("{\"origin\": [0, 0], \"outside\": {\"mode\": \"void\"}}", height, null, null);
        assertEquals(TerrainSampler.NONE, voidSampler.terrainY(-1, 0));
        TerrainSampler extend = sampler("{\"origin\": [0, 0], \"outside\": {\"mode\": \"extend\"}}", height, null, null);
        assertEquals(50, extend.terrainY(-100, 2));
        assertEquals(53, extend.terrainY(100, -50));
    }

    @Test
    void fluidsFillAboveTerrainAndLavaWins() throws IOException {
        GrayRaster height = raster(3, 1, (x, y, c) -> 40);
        GrayRaster water = raster(3, 1, (x, y, c) -> x == 0 ? 0 : 60);
        GrayRaster lava = raster(3, 1, (x, y, c) -> x == 2 ? 50 : 0);
        TerrainSampler s = sampler("{\"origin\": [0, 0]}", height, water, lava);
        TerrainSampler.Column column = new TerrainSampler.Column();

        s.sample(0, 0, column);
        assertEquals(TerrainSampler.NONE, column.fluidTop());

        s.sample(1, 0, column);
        assertEquals(60, column.fluidTop());
        assertFalse(column.isLava());

        s.sample(2, 0, column);
        assertEquals(60, column.fluidTop());
        assertTrue(column.lavaY == 50);
        // Water is higher here, so the column is water on top.
        assertFalse(column.isLava());
    }

    @Test
    void fluidBelowTerrainIsIgnored() throws IOException {
        TerrainSampler s = sampler("{\"origin\": [0, 0]}", raster(1, 1, (x, y, c) -> 80), raster(1, 1, (x, y, c) -> 70), null);
        assertEquals(TerrainSampler.NONE, s.sample(0, 0, new TerrainSampler.Column()).fluidTop());
    }

    @Test
    void horizontalScaleInterpolates() throws IOException {
        TerrainSampler s = sampler("{\"origin\": [0, 0], \"horizontal_scale\": 4}", raster(2, 1, (x, y, c) -> x == 0 ? 0 : 100), null, null);
        assertTrue(s.isInside(7, 0));
        assertFalse(s.isInside(8, 0));
        int previous = -1;
        for (int x = 0; x < 8; x++) {
            int y = s.terrainY(x, 0);
            assertTrue(y >= previous, "height should increase smoothly");
            previous = y;
        }
        assertEquals(0, s.terrainY(0, 0));
        assertEquals(100, s.terrainY(7, 0));
    }

    @Test
    void heightMappingForms() {
        HeightMapping linear = HeightMapping.resolve(config("{\"height\": {\"base_y\": -64, \"scale\": 2}}").height(), 8);
        assertEquals(-64, linear.toBlockY(0));
        assertEquals(446, linear.toBlockY(255));
        HeightMapping range = HeightMapping.resolve(config("{\"height\": {\"min_y\": 0, \"max_y\": 2000}}").height(), 16);
        assertEquals(0, range.toBlockY(0));
        assertEquals(2000, range.toBlockY(65535));
        HeightMapping default16 = HeightMapping.resolve(config("{}").height(), 16);
        assertEquals(255, default16.toBlockY(65535));
    }

    @Test
    void conflictingHeightFormsAreRejected() {
        ValidationReport report = new ValidationReport();
        MapConfig.parse(JsonParser.parseString("{\"height\": {\"base_y\": 0, \"max_y\": 100}}").getAsJsonObject(), report);
        assertTrue(report.hasErrors());
    }

    @Test
    void dimensionLayout() {
        ValidationReport report = new ValidationReport();
        assertEquals(DimensionLayout.VANILLA, DimensionLayout.compute(new MapConfig.Dimension(null, null), 0, 255, report));

        DimensionLayout tall = DimensionLayout.compute(new MapConfig.Dimension(null, null), -10, 1500, report);
        assertEquals(-64, tall.minY());
        assertTrue(tall.maxY() >= 1500 + DimensionLayout.HEADROOM);
        assertFalse(report.hasErrors());

        DimensionLayout tooTall = DimensionLayout.compute(new MapConfig.Dimension(null, null), -3000, 3000, report);
        assertEquals(DimensionLayout.LIMIT_MIN_Y, tooTall.minY());
        assertEquals(DimensionLayout.LIMIT_MAX_Y, tooTall.maxY());
        assertFalse(report.hasErrors());
        assertFalse(report.warnings().isEmpty());

        ValidationReport bad = new ValidationReport();
        DimensionLayout.compute(new MapConfig.Dimension(-60, 100), 0, 10, bad);
        assertTrue(bad.hasErrors());

        ValidationReport explicit = new ValidationReport();
        assertEquals(new DimensionLayout(-128, 512), DimensionLayout.compute(new MapConfig.Dimension(-128, 512), 0, 255, explicit));
        assertFalse(explicit.hasErrors());
    }

    @Test
    void featureFilter() {
        assertEquals(MapConfig.Features.ALL, config("{}").decoration().features());
        assertEquals(MapConfig.Features.NONE, config("{\"decoration\": {\"features\": false}}").decoration().features());

        MapConfig.Features features = config("""
                {"decoration": {"features": {"disabled": ["lake_lava_surface"], "disabled_steps": ["fluid_springs"]}}}
                """).decoration().features();
        assertFalse(features.allows("minecraft:lake_lava_surface", "lakes"));
        assertTrue(features.allows("minecraft:lake_lava_underground", "lakes"));
        assertFalse(features.allows("minecraft:spring_water", "fluid_springs"));
        assertTrue(features.allows("minecraft:trees_plains", "vegetal_decoration"));
        assertFalse(features.isUnfiltered());

        MapConfig.Features only = config("{\"decoration\": {\"features\": {\"only\": [\"minecraft:ore_iron_upper\"]}}}").decoration().features();
        assertTrue(only.allows("minecraft:ore_iron_upper", "underground_ores"));
        assertFalse(only.allows("minecraft:ore_gold", "underground_ores"));

        assertTrue(MapConfig.Features.ALL.isUnfiltered());
        assertEquals(MapConfig.Features.ALL, config("{\"decoration\": {\"features\": null}}").decoration().features());
        MapConfig.Features disabled = config("{\"decoration\": {\"features\": {\"enabled\": false}}}").decoration().features();
        assertFalse(disabled.allows("minecraft:trees_plains", "vegetal_decoration"));
        assertTrue(disabled.isUnfiltered());
        assertTrue(config("{\"decoration\": {\"features\": {\"disabled_steps\": [\"LAKES\"]}}}").decoration().features().disabledSteps()
                .contains("lakes"));

        for (String steps : new String[]{"[\"lake\"]", "[3]"}) {
            ValidationReport report = new ValidationReport();
            MapConfig.parse(JsonParser.parseString("{\"decoration\": {\"features\": {\"disabled_steps\": " + steps + "}}}").getAsJsonObject(), report);
            assertTrue(report.hasErrors(), steps);
        }
    }

    @Test
    void carvers() {
        assertEquals(MapConfig.Carvers.ALL, config("{}").decoration().carvers());
        assertEquals(MapConfig.Carvers.NONE, config("{\"decoration\": {\"carvers\": false}}").decoration().carvers());
        MapConfig.Carvers caves = config("{\"decoration\": {\"carvers\": {\"canyon\": false}}}").decoration().carvers();
        assertEquals(new MapConfig.Carvers(true, false, true), caves);
        assertTrue(caves.any());
        assertFalse(MapConfig.Carvers.NONE.any());
        // Noise caves follow "cave" unless set
        assertEquals(new MapConfig.Carvers(false, true, false), config("{\"decoration\": {\"carvers\": {\"cave\": false}}}").decoration().carvers());
        assertEquals(new MapConfig.Carvers(false, false, true),
                config("{\"decoration\": {\"carvers\": {\"cave\": false, \"canyon\": false, \"noise\": true}}}").decoration().carvers());
        assertTrue(new MapConfig.Carvers(false, false, true).any());
        assertTrue(new MapConfig.Carvers(false, true, false).any());

        for (String carvers : new String[]{"3", "{\"cave\": \"yes\"}"}) {
            ValidationReport report = new ValidationReport();
            MapConfig.parse(JsonParser.parseString("{\"decoration\": {\"carvers\": " + carvers + "}}").getAsJsonObject(), report);
            assertTrue(report.hasErrors(), carvers);
        }
    }

    @Test
    void defaultBlockPalette() {
        assertEquals(List.of("minecraft:stone"), config("{}").blocks().defaultBlock().ids());
        assertEquals(List.of("minecraft:stone", "minecraft:deepslate"),
                config("{\"blocks\": {\"default\": [\"stone\", \"deepslate\"]}}").blocks().defaultBlock().ids());
        ValidationReport report = new ValidationReport();
        MapConfig invalid = MapConfig.parse(JsonParser.parseString("{\"blocks\": {\"default\": []}}").getAsJsonObject(), report);
        assertTrue(report.hasErrors());
        assertEquals(List.of("minecraft:stone"), invalid.blocks().defaultBlock().ids());
    }

    @Test
    void outsideBorder() {
        assertTrue(config("{}").outside().border());
        assertTrue(config("{\"outside\": {\"mode\": \"void\"}}").outside().border());
        assertFalse(config("{\"outside\": {\"border\": false}}").outside().border());
    }

    @Test
    void snowRange() {
        assertEquals(MapConfig.Snow.ALL, config("{}").snow());
        assertEquals(MapConfig.Snow.NONE, config("{\"snow\": false}").snow());
        MapConfig.Snow snow = config("{\"snow\": {\"min_y\": 100, \"max_y\": 200}}").snow();
        assertFalse(snow.allows(99));
        assertTrue(snow.allows(100));
        assertTrue(snow.allows(200));
        assertFalse(snow.allows(201));
        assertTrue(config("{\"snow\": {\"max_y\": 200}}").snow().allows(-64));

        for (String json : new String[]{"{\"snow\": {\"min_y\": 300, \"max_y\": 200}}", "{\"snow\": 3}"}) {
            ValidationReport report = new ValidationReport();
            MapConfig.parse(JsonParser.parseString(json).getAsJsonObject(), report);
            assertTrue(report.hasErrors(), json);
        }
    }

    @Test
    void biomeTransitionRange() {
        assertEquals(MapConfig.DEFAULT_BIOME_TRANSITION, config("{\"$schema\": \"x\"}").biomeTransition());
        assertEquals(0, config("{\"biome_transition\": 0}").biomeTransition());
        ValidationReport report = new ValidationReport();
        MapConfig.parse(JsonParser.parseString("{\"biome_transition\": 100}").getAsJsonObject(), report);
        assertTrue(report.hasErrors());
        ValidationReport negative = new ValidationReport();
        MapConfig.parse(JsonParser.parseString("{\"biome_transition\": -1}").getAsJsonObject(), negative);
        assertTrue(negative.hasErrors());
    }

    @Test
    void vanillaStructureFilter() {
        MapConfig.VanillaStructures filter = config("{\"vanilla_structures\": {\"disabled\": [\"village_plains\", \"minecraft:strongholds\"]}}")
                .vanillaStructures();
        assertFalse(filter.allows("minecraft:villages", "minecraft:village_plains"));
        assertTrue(filter.allows("minecraft:villages", "minecraft:village_desert"));
        assertFalse(filter.allows("minecraft:strongholds", "minecraft:stronghold"));

        MapConfig.VanillaStructures only = config("{\"vanilla_structures\": {\"only\": [\"minecraft:villages\"]}}").vanillaStructures();
        assertTrue(only.allows("minecraft:villages", "minecraft:village_plains"));
        assertFalse(only.allows("minecraft:igloos", "minecraft:igloo"));

        assertFalse(config("{\"vanilla_structures\": false}").vanillaStructures().allows("a:b", "a:c"));
    }
}
