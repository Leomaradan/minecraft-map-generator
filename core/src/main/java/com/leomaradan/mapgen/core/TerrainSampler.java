package com.leomaradan.mapgen.core;

/**
 * Converts world block coordinates into terrain columns: surface height, water and lava surfaces
 * and biome. Pure function of the map pack; thread safe.
 */
public final class TerrainSampler {
    /** Marker for "no block" (void terrain, no fluid). */
    public static final int NONE = Integer.MIN_VALUE;

    private final GrayRaster heightmap;
    private final HeightMapping mapping;
    private final GrayRaster water;
    private final GrayRaster lava;
    private final BiomeRaster biomes;
    private final MapConfig.Outside outside;
    private final int originX;
    private final int originZ;
    private final double scale;
    private final int width;
    private final int height;

    /** Mutable result of {@link #sample}, reused to avoid allocations in the generator's hot loop. */
    public static final class Column {
        /** Y of the top solid block, or {@link #NONE} for an empty column. */
        public int terrainY;
        /** Y of the top water block, or {@link #NONE}. Only meaningful when above {@link #terrainY}. */
        public int waterY;
        /** Y of the top lava block, or {@link #NONE}. Lava wins over water. */
        public int lavaY;
        /** Index in the biome mapping, or -1 for the outside biome. */
        public int biome;
        public boolean inside;

        /** Y of the highest fluid block above the terrain, or {@link #NONE}. */
        public int fluidTop() {
            int top = Math.max(this.waterY, this.lavaY);
            return top > this.terrainY ? top : NONE;
        }

        public boolean isLava() {
            return this.lavaY != NONE && this.lavaY > this.terrainY && this.lavaY >= this.waterY;
        }
    }

    public TerrainSampler(MapConfig config, GrayRaster heightmap, HeightMapping mapping, GrayRaster water, GrayRaster lava, BiomeRaster biomes) {
        this.heightmap = heightmap;
        this.mapping = mapping;
        this.water = water;
        this.lava = lava;
        this.biomes = biomes;
        this.outside = config.outside();
        this.scale = config.horizontalScale();
        this.width = heightmap.width();
        this.height = heightmap.height();
        // Default origin: the map is centered on X=0, Z=0.
        this.originX = config.originX() != null ? config.originX() : -(int) Math.floor(this.width * this.scale / 2);
        this.originZ = config.originZ() != null ? config.originZ() : -(int) Math.floor(this.height * this.scale / 2);
    }

    public int originX() {
        return this.originX;
    }

    public int originZ() {
        return this.originZ;
    }

    /** World X of the block just past the east edge of the map. */
    public int endX() {
        return this.originX + (int) Math.ceil(this.width * this.scale);
    }

    public int endZ() {
        return this.originZ + (int) Math.ceil(this.height * this.scale);
    }

    public boolean isInside(int x, int z) {
        double px = (x - this.originX) / this.scale;
        double pz = (z - this.originZ) / this.scale;
        return px >= 0 && pz >= 0 && px < this.width && pz < this.height;
    }

    public Column sample(int x, int z, Column out) {
        boolean inside = this.isInside(x, z);
        out.inside = inside;
        if (!inside && this.outside.mode() != MapConfig.OutsideMode.EXTEND) {
            out.biome = -1;
            if (this.outside.mode() == MapConfig.OutsideMode.VOID) {
                out.terrainY = NONE;
                out.waterY = NONE;
            } else {
                out.terrainY = this.outside.groundLevel();
                out.waterY = this.outside.waterLevel() > this.outside.groundLevel() ? this.outside.waterLevel() : NONE;
            }
            out.lavaY = NONE;
            return out;
        }

        // Pixel coordinates; when extending the border, outside positions clamp to the edge.
        double u = (x - this.originX + 0.5) / this.scale - 0.5;
        double v = (z - this.originZ + 0.5) / this.scale - 0.5;
        int px = clamp((int) Math.floor((x - this.originX) / this.scale), this.width);
        int pz = clamp((int) Math.floor((z - this.originZ) / this.scale), this.height);

        out.terrainY = this.mapping.toBlockY(this.scale > 1 ? this.bilinear(u, v) : this.heightmap.value(px, pz));
        out.waterY = this.fluid(this.water, px, pz);
        out.lavaY = this.fluid(this.lava, px, pz);
        out.biome = this.biomes != null ? this.biomes.index(px, pz) : -1;
        return out;
    }

    /** Terrain height only; {@link #NONE} for void columns. */
    public int terrainY(int x, int z) {
        return this.sample(x, z, new Column()).terrainY;
    }

    /** Biome mapping index at a block position, -1 for the outside biome. */
    public int biomeIndex(int x, int z) {
        if (this.biomes == null) {
            return -1;
        }
        if (!this.isInside(x, z) && this.outside.mode() != MapConfig.OutsideMode.EXTEND) {
            return -1;
        }
        int px = clamp((int) Math.floor((x - this.originX) / this.scale), this.width);
        int pz = clamp((int) Math.floor((z - this.originZ) / this.scale), this.height);
        return this.biomes.index(px, pz);
    }

    private int fluid(GrayRaster raster, int px, int pz) {
        if (raster == null) {
            return NONE;
        }
        int value = raster.value(px, pz);
        return value == 0 ? NONE : this.mapping.toBlockY(value);
    }

    private double bilinear(double u, double v) {
        int x0 = (int) Math.floor(u);
        int z0 = (int) Math.floor(v);
        double tx = u - x0;
        double tz = v - z0;
        int xa = clamp(x0, this.width);
        int xb = clamp(x0 + 1, this.width);
        int za = clamp(z0, this.height);
        int zb = clamp(z0 + 1, this.height);
        double top = this.heightmap.value(xa, za) * (1 - tx) + this.heightmap.value(xb, za) * tx;
        double bottom = this.heightmap.value(xa, zb) * (1 - tx) + this.heightmap.value(xb, zb) * tx;
        return top * (1 - tz) + bottom * tz;
    }

    private static int clamp(int value, int size) {
        return value < 0 ? 0 : Math.min(value, size - 1);
    }
}
