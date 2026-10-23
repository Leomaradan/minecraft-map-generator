package com.leomaradan.mapgen.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A loaded and validated map pack (the ZIP file). Immutable and thread safe.
 *
 * @param id           stable identifier derived from the ZIP content (hex SHA-1 prefix)
 * @param name         display name ({@code name} in map.json, or the ZIP file name)
 * @param source       the ZIP file it was read from
 * @param heightmap    required
 * @param biomes       null when the pack has no biome map (the outside biome is used everywhere)
 * @param water        null when absent
 * @param lava         null when absent
 * @param templates    raw {@code .nbt} files referenced by template structures, by ZIP path
 * @param datapack     files of the embedded datapacks of the whole ZIP, by path relative to the datapack folder
 * @param lowestY      lowest block Y the map produces (terrain, fluids, outside ground)
 * @param highestY     highest block Y the map produces
 */
public record MapPack(
        String id,
        String name,
        Path source,
        MapConfig config,
        GrayRaster heightmap,
        HeightMapping heightMapping,
        BiomeRaster biomes,
        BiomeMapping biomeMapping,
        GrayRaster water,
        GrayRaster lava,
        List<StructureEntry> structures,
        Map<String, byte[]> templates,
        Map<String, byte[]> datapack,
        DimensionLayout layout,
        TerrainSampler sampler,
        int lowestY,
        int highestY
) {
    /** The dimension this map generates. */
    public MapConfig.DimensionTarget dimension() {
        return this.config.target();
    }

    public int width() {
        return this.heightmap.width();
    }

    public int height() {
        return this.heightmap.height();
    }

    public boolean hasDatapack() {
        return !this.datapack.isEmpty();
    }

    /** All biome ids the map can produce: mapped biomes and their substitutes, then the outside biome. */
    public List<String> biomeIds() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (BiomeMapping.Variant v : this.biomeMapping.slots()) {
            ids.add(v.biome());
        }
        ids.add(this.config.outside().biome());
        return List.copyOf(ids);
    }

    /** All block ids named by the pack ({@code blocks.default} and the surface layers of biomes.json), without duplicates. */
    public List<String> blockIds() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(this.config.blocks().defaultBlock().ids());
        for (BiomeMapping.Variant v : this.biomeMapping.slots()) {
            ids.addAll(v.blockIds());
        }
        return List.copyOf(ids);
    }

    /** One-line summary for the UI and logs. */
    public String summary() {
        return this.terrainSummary() + (this.hasDatapack() ? ", datapack" : "");
    }

    /** {@link #summary()} without the datapack (shared by the maps of a ZIP). */
    public String terrainSummary() {
        return this.width() + "x" + this.height() + " px, " + this.heightmap.bitDepth() + "-bit, Y " + this.layout.minY() + ".."
                + this.layout.maxY() + ", " + this.biomeMapping.entries().size() + " biome(s), " + this.structures.size() + " structure(s)";
    }
}
