package com.leomaradan.mapgen.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPaletteTest {

    private static BlockPalette parse(String value, ValidationReport report) {
        JsonObject parent = JsonParser.parseString("{\"top\": " + value + "}").getAsJsonObject();
        return BlockPalette.parse(parent, "top", "biomes.json[\"#000000\"]", report);
    }

    private static BlockPalette parse(String value) {
        ValidationReport report = new ValidationReport();
        BlockPalette palette = parse(value, report);
        assertFalse(report.hasErrors(), report.toString());
        return palette;
    }

    /** Share of each block over a 64 x 8 x 64 area. */
    private static double[] shares(BlockPalette palette) {
        double[] counts = new double[palette.blocks().size()];
        int total = 0;
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 8; y++) {
                for (int z = 0; z < 64; z++) {
                    counts[palette.pick(x, y * 5, z)]++;
                    total++;
                }
            }
        }
        for (int i = 0; i < counts.length; i++) {
            counts[i] /= total;
        }
        return counts;
    }

    @Test
    void singleBlock() {
        BlockPalette palette = parse("\"stone\"");
        assertEquals(List.of("minecraft:stone"), palette.ids());
        assertEquals(0, palette.pick(12, 34, 56));
        assertEquals("minecraft:stone", palette.toString());
        assertNull(BlockPalette.parse(new JsonObject(), "top", "x", new ValidationReport()));
    }

    @Test
    void listWithWeights() {
        BlockPalette palette = parse("[{\"block\": \"minecraft:stone\", \"weight\": 3}, \"dirt\"]");
        assertEquals(List.of(new BlockPalette.Choice("minecraft:stone", 3), new BlockPalette.Choice("minecraft:dirt", 1)), palette.blocks());
        assertEquals(BlockPalette.Method.RANDOM, palette.method());
        double[] shares = shares(palette);
        assertEquals(0.75, shares[0], 0.02);
        assertEquals(0.25, shares[1], 0.02);
        assertEquals(palette.pick(5, 6, 7), palette.pick(5, 6, 7), "same position, same block");
    }

    @Test
    void perlinFormsPatchesWithTheWeights() {
        BlockPalette palette = parse("{\"blocks\": [{\"block\": \"stone\", \"weight\": 3}, \"dirt\"], \"method\": \"PERLIN\", \"scale\": 8}");
        assertEquals(BlockPalette.Method.PERLIN, palette.method());
        assertEquals(8, palette.scale());
        double[] shares = shares(palette);
        assertEquals(0.75, shares[0], 0.1);
        // Patches: neighbors are mostly the same block, unlike random picks.
        int same = 0;
        for (int x = 0; x < 256; x++) {
            if (palette.pick(x, 0, 0) == palette.pick(x + 1, 0, 0)) {
                same++;
            }
        }
        assertTrue(same > 230, "same neighbors: " + same);
        assertEquals("perlin" + palette.blocks(), palette.toString());
    }

    @Test
    void hugeWeightsDontOverflow() {
        double[] shares = shares(parse("[{\"block\": \"stone\", \"weight\": 1e308}, {\"block\": \"dirt\", \"weight\": 1e308}]"));
        assertEquals(0.5, shares[0], 0.02);
    }

    @Test
    void perlinAtScaleOneStillMixes() {
        // Every position falls on the noise lattice at scale 1 without the offset: the noise is 0 there.
        double[] shares = shares(parse("{\"blocks\": [\"stone\", \"dirt\"], \"method\": \"perlin\", \"scale\": 1}"));
        assertEquals(0.5, shares[0], 0.1);
    }

    @Test
    void objectDefaultsToRandom() {
        BlockPalette palette = parse("{\"blocks\": [\"stone\", \"andesite\"]}");
        assertEquals(BlockPalette.Method.RANDOM, palette.method());
        assertEquals(BlockPalette.DEFAULT_SCALE, palette.scale());
        assertEquals(0.5, shares(palette)[0], 0.02);
    }

    @Test
    void layersGetDifferentPatterns() {
        JsonObject parent = JsonParser.parseString("{\"top\": [\"stone\", \"dirt\"], \"filler\": [\"stone\", \"dirt\"]}").getAsJsonObject();
        BlockPalette top = BlockPalette.parse(parent, "top", "p", new ValidationReport());
        BlockPalette filler = BlockPalette.parse(parent, "filler", "p", new ValidationReport());
        int differences = 0;
        for (int x = 0; x < 100; x++) {
            if (top.pick(x, 0, 0) != filler.pick(x, 0, 0)) {
                differences++;
            }
        }
        assertTrue(differences > 20, "differences: " + differences);
    }

    @Test
    void errors() {
        String[][] cases = {
                {"3", "must be a block id, a list of blocks"},
                {"\"Not Valid\"", "is not a valid resource id"},
                {"[]", "must not be empty"},
                {"[3]", "must be a block id or {\"block\""},
                {"[{\"weight\": 2}]", "top[0].block is required"},
                {"[{\"block\": \"stone\", \"weight\": 0}]", "weight must be greater than 0"},
                {"{\"method\": \"perlin\"}", "top.blocks is required"},
                {"{\"blocks\": [\"stone\"], \"method\": \"voronoi\"}", "method must be \"random\" or \"perlin\""},
                {"{\"blocks\": [\"stone\"], \"scale\": -1}", "scale must be greater than 0"},
        };
        for (String[] c : cases) {
            ValidationReport report = new ValidationReport();
            parse(c[0], report);
            assertTrue(report.errors().stream().anyMatch(m -> m.contains(c[1])), c[0] + " -> " + report);
        }
    }
}
