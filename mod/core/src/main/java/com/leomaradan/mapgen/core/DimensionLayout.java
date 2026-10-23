package com.leomaradan.mapgen.core;

/**
 * Vertical extent of the dimension of a map. When it differs from the vanilla dimension
 * ({@link #baseline}), the mod generates a datapack overriding its dimension type with these values.
 */
public record DimensionLayout(int minY, int height) {
    public static final int VANILLA_MIN_Y = -64;
    public static final int VANILLA_HEIGHT = 384;
    /** Limits of {@code DimensionType}. */
    public static final int LIMIT_MIN_Y = -2032;
    public static final int LIMIT_MAX_Y = 2031;
    public static final int LIMIT_HEIGHT = 4064;
    /** Room kept above the highest terrain for trees, structures and building. */
    public static final int HEADROOM = 64;
    /** Room kept below the lowest terrain for bedrock and caves. */
    public static final int FOOTROOM = 16;

    public static final DimensionLayout VANILLA = new DimensionLayout(VANILLA_MIN_Y, VANILLA_HEIGHT);
    /** The Nether and the End: Y 0..255. */
    public static final DimensionLayout NETHER_END = new DimensionLayout(0, 256);

    /** Height of the vanilla dimension a map replaces (the overworld's for new dimensions). */
    public static DimensionLayout baseline(MapConfig.DimensionTarget target) {
        return switch (target.id()) {
            case MapConfig.DimensionTarget.NETHER, MapConfig.DimensionTarget.END -> NETHER_END;
            default -> VANILLA;
        };
    }

    public int maxY() {
        return this.minY + this.height - 1;
    }

    public boolean isVanilla() {
        return this.minY == VANILLA_MIN_Y && this.height == VANILLA_HEIGHT;
    }

    /**
     * @param lowestY  lowest block Y the map produces (terrain, fluids, outside ground)
     * @param highestY highest block Y the map produces
     */
    public static DimensionLayout compute(MapConfig.Dimension explicit, int lowestY, int highestY, ValidationReport report) {
        return compute(explicit, VANILLA, lowestY, highestY, report);
    }

    /**
     * @param baseline the smallest extent: the vanilla height of the dimension
     */
    public static DimensionLayout compute(MapConfig.Dimension explicit, DimensionLayout baseline, int lowestY, int highestY,
                                          ValidationReport report) {
        int autoMin = Math.min(baseline.minY(), floor16(lowestY - FOOTROOM));
        int autoMax = Math.max(baseline.minY() + baseline.height(), ceil16(highestY + HEADROOM + 1));

        int minY = explicit.minY() != null ? explicit.minY() : autoMin;
        int height = explicit.height() != null ? explicit.height() : autoMax - minY;

        if (explicit.minY() != null && minY % 16 != 0) {
            report.error("map.json.dimension_parameter.min_y must be a multiple of 16");
            minY = floor16(minY);
        }
        if (explicit.height() != null && height % 16 != 0) {
            report.error("map.json.dimension_parameter.height must be a multiple of 16");
            height = ceil16(height);
        }
        if (explicit.minY() == null && minY < LIMIT_MIN_Y) {
            report.warn("the map goes below Y=" + LIMIT_MIN_Y + ", the lowest Y Minecraft supports; the bottom is cut");
            minY = LIMIT_MIN_Y;
        }
        if (explicit.height() == null && minY + height - 1 > LIMIT_MAX_Y) {
            report.warn("the map goes above Y=" + LIMIT_MAX_Y + ", the highest Y Minecraft supports; the top is cut");
            height = LIMIT_MAX_Y + 1 - minY;
        }
        if (minY < LIMIT_MIN_Y || height < 16 || height > LIMIT_HEIGHT || minY + height - 1 > LIMIT_MAX_Y) {
            report.error("map.json.dimension_parameter: min_y=" + minY + " height=" + height + " is outside Minecraft limits (min_y >= "
                    + LIMIT_MIN_Y + ", 16 <= height <= " + LIMIT_HEIGHT + ", min_y + height <= " + (LIMIT_MAX_Y + 1) + ")");
            return baseline;
        }
        if (lowestY < minY + 1) {
            report.warn("terrain reaches Y=" + lowestY + ", below the bottom of the world (" + minY + "); it is cut");
        }
        if (highestY > minY + height - 1) {
            report.warn("terrain reaches Y=" + highestY + ", above the top of the world (" + (minY + height - 1) + "); it is cut");
        }
        return new DimensionLayout(minY, height);
    }

    static int floor16(int y) {
        return Math.floorDiv(y, 16) * 16;
    }

    static int ceil16(int y) {
        return -Math.floorDiv(-y, 16) * 16;
    }
}
