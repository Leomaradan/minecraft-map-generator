package com.leomaradan.mapgen.core;

/**
 * Converts world block coordinates into terrain columns: surface height, water and lava surfaces
 * and biome. Pure function of the map pack; thread safe.
 * <p>
 * Biome borders are shifted by a smooth noise ({@code biome_transition}) so they follow natural
 * curves instead of pixel edges; then the substitutes of the entry (river, mountain, border,
 * underground) are applied.
 */
public final class TerrainSampler {
    /** Marker for "no block" (void terrain, no fluid). */
    public static final int NONE = Integer.MIN_VALUE;

    private final GrayRaster heightmap;
    private final HeightMapping mapping;
    private final GrayRaster water;
    private final GrayRaster lava;
    private final BiomeRaster biomes;
    private final BiomeMapping biomeMapping;
    private final MapConfig.Outside outside;
    /** Heightmap pixels at 0 are empty (no block, no fluid): void outside and no bedrock, for floating islands. */
    private final boolean emptyAtZero;
    private final MapConfig.Snow snow;
    /** False when snow is allowed everywhere: no need to sample the column. */
    private final boolean hasSnowRules;
    private final int defaultMountainY;
    /** Largest biome border shift in blocks, 0 for exact pixel borders. */
    private final int transition;
    private final double transitionWavelength;
    /** Noise shifting the biome map along X and Z ({@code biome_transition}). */
    private final PerlinNoise warpX = new PerlinNoise(0x5EED_0001L);
    private final PerlinNoise warpZ = new PerlinNoise(0x5EED_0002L);
    /** Distance in pixels to the nearest water pixel, null when no entry has a river biome. */
    private final byte[] waterDistance;
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
        /** Biome slot at the surface (see {@link BiomeMapping}), or -1 for the outside biome. */
        public int biome = -1;
        /** Biome slot from {@link #undergroundTop} down, or -1 when the column has no underground biome. */
        public int undergroundBiome = -1;
        /** Highest Y using {@link #undergroundBiome}. */
        public int undergroundTop;
        public boolean inside;

        /** Biome slot at a given height. */
        public int biomeAt(int y) {
            return this.undergroundBiome >= 0 && y <= this.undergroundTop ? this.undergroundBiome : this.biome;
        }

        /** Y of the highest fluid block above the terrain, or {@link #NONE}. */
        public int fluidTop() {
            int top = Math.max(this.waterY, this.lavaY);
            return top > this.terrainY ? top : NONE;
        }

        public boolean isLava() {
            return this.lavaY != NONE && this.lavaY > this.terrainY && this.lavaY >= this.waterY;
        }
    }

    public TerrainSampler(MapConfig config, GrayRaster heightmap, HeightMapping mapping, GrayRaster water, GrayRaster lava, BiomeRaster biomes,
                          BiomeMapping biomeMapping) {
        this.heightmap = heightmap;
        this.mapping = mapping;
        this.water = water;
        this.lava = lava;
        this.biomes = biomes;
        this.biomeMapping = biomeMapping;
        this.outside = config.outside();
        this.emptyAtZero = this.outside.mode() == MapConfig.OutsideMode.VOID && !config.blocks().bedrock();
        this.snow = config.snow();
        this.hasSnowRules = !this.snow.equals(MapConfig.Snow.ALL) || biomeMapping.slots().stream().anyMatch(v -> v.snow() != null);
        this.defaultMountainY = config.seaLevel() + BiomeMapping.DEFAULT_MOUNTAIN_ABOVE_SEA;
        this.transition = config.biomeTransition();
        // The shift (up to 2 x 3.6 x transition / wavelength per block) changes by less than one block per block:
        // features 2 pixels wide stay connected (1-pixel lines can have gaps where consecutive blocks skip a pixel).
        this.transitionWavelength = Math.max(8, 7.5 * this.transition);
        this.scale = config.horizontalScale();
        this.width = heightmap.width();
        this.height = heightmap.height();
        // Default origin: the map is centered on X=0, Z=0.
        this.originX = config.originX() != null ? config.originX() : -(int) Math.floor(this.width * this.scale / 2);
        this.originZ = config.originZ() != null ? config.originZ() : -(int) Math.floor(this.height * this.scale / 2);
        // Only read when there is a biome map to put the rivers on.
        this.waterDistance = water != null && biomes != null && biomeMapping.hasRivers() ? this.computeWaterDistance() : null;
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

    /** World X of the center of the pixel column {@code px}. */
    public int pixelToWorldX(int px) {
        return this.originX + (int) Math.floor((px + 0.5) * this.scale);
    }

    /** World Z of the center of the pixel row {@code pz}. */
    public int pixelToWorldZ(int pz) {
        return this.originZ + (int) Math.floor((pz + 0.5) * this.scale);
    }

    public boolean isPixelInside(int px, int pz) {
        return px >= 0 && pz >= 0 && px < this.width && pz < this.height;
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
            out.undergroundBiome = -1;
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

        if (this.emptyAtZero && this.heightmap.value(px, pz) == 0) {
            out.terrainY = NONE;
            out.waterY = NONE;
            out.lavaY = NONE;
            this.resolveBiome(x, z, px, pz, out);
            return out;
        }
        out.terrainY = this.mapping.toBlockY(this.scale > 1 ? this.bilinear(u, v) : this.heightmap.value(px, pz));
        out.waterY = this.fluid(this.water, px, pz);
        out.lavaY = this.fluid(this.lava, px, pz);
        this.resolveBiome(x, z, px, pz, out);
        return out;
    }

    /** Water pixels (water above the ground, not covered by lava) are at distance 0. */
    private byte[] computeWaterDistance() {
        short[] d = new short[this.width * this.height];
        for (int pz = 0; pz < this.height; pz++) {
            for (int px = 0; px < this.width; px++) {
                int ground = this.mapping.toBlockY(this.heightmap.value(px, pz));
                int waterY = this.fluid(this.water, px, pz);
                int lavaY = this.fluid(this.lava, px, pz);
                boolean water = waterY != NONE && waterY > ground && (lavaY == NONE || lavaY <= ground || lavaY < waterY);
                d[pz * this.width + px] = water ? 0 : DistanceField.FAR;
            }
        }
        return DistanceField.compute(d, this.width, this.height);
    }

    /**
     * Sets the biome slots of a sampled column (terrain and fluids must be set).
     *
     * @param px pixel of the column, used for the water distance (rivers follow the water map exactly)
     */
    private void resolveBiome(int x, int z, int px, int pz, Column out) {
        out.undergroundBiome = -1;
        if (this.biomes == null) {
            out.biome = -1;
            return;
        }
        double bx = x;
        double bz = z;
        if (this.transition > 0) {
            bx += this.transition * this.warp(this.warpX, x, z);
            bz += this.transition * this.warp(this.warpZ, x, z);
        }
        int bpx = clamp((int) Math.floor((bx - this.originX) / this.scale), this.width);
        int bpz = clamp((int) Math.floor((bz - this.originZ) / this.scale), this.height);
        int index = this.biomes.index(bpx, bpz);
        if (index < 0 || index >= this.biomeMapping.entries().size()) {
            out.biome = index;
            return;
        }
        BiomeMapping.Entry entry = this.biomeMapping.entries().get(index);
        int slot = index;
        if (entry.hasRiver() && !out.isLava() && (out.fluidTop() != NONE || this.isRiverBank(px, pz, entry.riverWidth()))) {
            slot = entry.riverSlot();
        } else if (entry.mountainSlot() >= 0 && out.terrainY >= (entry.mountainY() != null ? entry.mountainY() : this.defaultMountainY)) {
            slot = entry.mountainSlot();
        } else if (entry.hasBorder() && this.isBorder(bpx, bpz, entry.borderWidth())) {
            slot = entry.borderSlot();
        }
        out.biome = slot;
        if (entry.undergroundSlot() >= 0) {
            out.undergroundBiome = entry.undergroundSlot();
            out.undergroundTop = out.terrainY - entry.undergroundDepth();
        }
    }

    /**
     * True if the pixel is within {@code width} blocks of a water pixel. A distance capped at {@link DistanceField#MAX}
     * is farther than any width (it matters when a pixel is smaller than a quarter of a block); same for borders.
     */
    private boolean isRiverBank(int px, int pz, int width) {
        if (this.waterDistance == null) {
            return false;
        }
        int distance = this.waterDistance[pz * this.width + px] & 0xFF;
        return distance < DistanceField.MAX && distance * this.scale <= width;
    }

    /** True if the biome pixel is less than {@code width} blocks from a pixel of another entry. */
    private boolean isBorder(int bpx, int bpz, int width) {
        int distance = this.biomes.borderDistance(bpx, bpz);
        return distance < BiomeRaster.MAX_BORDER_DISTANCE && distance * this.scale < width;
    }

    /** Smooth noise in [-1, 1]: Perlin noise, doubled so the shift often reaches the full transition. */
    private double warp(PerlinNoise noise, int x, int z) {
        double n = 2 * noise.noise(x / this.transitionWavelength, 0.5, z / this.transitionWavelength);
        return Math.clamp(n, -1.0, 1.0);
    }

    /** Terrain height only; {@link #NONE} for void columns. */
    public int terrainY(int x, int z) {
        return this.sample(x, z, new Column()).terrainY;
    }

    /** Biome slot at the surface of a block position, -1 for the outside biome. */
    public int biomeIndex(int x, int z) {
        if (this.biomes == null) {
            return -1;
        }
        return this.sample(x, z, new Column()).biome;
    }

    /** Biome slot at a block position, -1 for the outside biome. */
    public int biomeIndex(int x, int y, int z) {
        if (this.biomes == null) {
            return -1;
        }
        return this.sample(x, z, new Column()).biomeAt(y);
    }

    /** True if snow and ice may form at a block position: {@code snow} of the surface biome entry, else of map.json. */
    public boolean allowsSnow(int x, int y, int z) {
        if (!this.hasSnowRules) {
            return true;
        }
        int slot = this.biomeIndex(x, z);
        MapConfig.Snow rule = slot >= 0 && slot < this.biomeMapping.slots().size() ? this.biomeMapping.slots().get(slot).snow() : null;
        return (rule != null ? rule : this.snow).allows(y);
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
