package com.leomaradan.mapgen.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A map ZIP: one map per dimension. A {@code map.json} in the single format holds one map, the
 * {@code "dimensions"} format several. Immutable and thread safe.
 *
 * @param id         stable identifier derived from the ZIP content (hex SHA-1 prefix)
 * @param name       display name (the first map's name, or the ZIP file name)
 * @param source     the ZIP file it was read from
 * @param dimensions the maps, in map.json order; their dimensions are all different
 */
public record MapArchive(String id, String name, Path source, List<MapPack> dimensions) {
    public MapArchive {
        dimensions = List.copyOf(dimensions);
    }

    /**
     * The map of a dimension, by dimension id. An empty id (worlds created before dimensions
     * existed) means the first map.
     *
     * @throws IllegalArgumentException when the ZIP has no map for that dimension
     */
    public MapPack get(String dimension) {
        if (dimension.isEmpty()) {
            return this.dimensions.getFirst();
        }
        for (MapPack pack : this.dimensions) {
            if (pack.dimension().id().equals(dimension)) {
                return pack;
            }
        }
        throw new IllegalArgumentException("the map " + this.name + " has no dimension " + dimension);
    }

    /** The map of the overworld, or null when the ZIP doesn't generate the overworld. */
    public MapPack overworld() {
        return this.dimensions.stream().filter(p -> p.dimension().isOverworld()).findFirst().orElse(null);
    }

    /** Files of the embedded datapacks of all maps, by path relative to the datapack folder. */
    public Map<String, byte[]> datapack() {
        return this.dimensions.getFirst().datapack();
    }

    public boolean hasDatapack() {
        return !this.datapack().isEmpty();
    }

    /** One-line summary for the UI and logs. */
    public String summary() {
        if (this.dimensions.size() == 1) {
            MapPack pack = this.dimensions.getFirst();
            return (pack.dimension().isOverworld() ? "" : pack.dimension().id() + ", ") + pack.summary();
        }
        return this.dimensions.size() + " dimensions: " + this.dimensions.stream().map(p -> p.dimension().id() + " (" + p.terrainSummary() + ")")
                .collect(Collectors.joining("; ")) + (this.hasDatapack() ? "; datapack" : "");
    }
}
