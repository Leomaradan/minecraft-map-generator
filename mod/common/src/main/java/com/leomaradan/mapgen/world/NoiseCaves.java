package com.leomaradan.mapgen.world;

import com.leomaradan.mapgen.core.TerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's noise caves (large "cheese" caverns, spaghetti and noodle caves, cave entrances,
 * pillars), dug into the map's terrain.
 * <p>
 * The components are vanilla's own density functions (the {@code overworld/caves/*} entries and
 * the cave noises of {@code NoiseRouterData.underground}). Vanilla combines them with
 * {@code sloped_cheese}, which measures the depth below its own terrain; here the depth comes from
 * the map. Like vanilla, the components are sampled on a grid of cells (4 blocks wide, 8 tall)
 * and interpolated inside each cell.
 * <p>
 * Caves are dry: no aquifers, lava only at the very bottom like the carvers. They never open into
 * the map's water and lava.
 */
public final class NoiseCaves {
    private static final int CELL_XZ = 4;
    private static final int CELL_Y = 8;
    /** Vanilla's sloped_cheese grows by about 1 every 32 blocks of depth. */
    private static final float DEPTH_SCALE = 32.0f;
    /** Below this "sloped cheese", only entrances (and noodles) dig: no caverns near the surface. */
    private static final float SURFACE_LIMIT = 1.5625f;
    /** Solid blocks kept under the ground of columns next to the map's fluids. */
    private static final int FLUID_COVER = 8;
    /** Lava below this many blocks above the bottom of the world, as the carvers do. */
    private static final int LAVA_DEPTH = 8;

    private static final int CAVERNS = 0;
    private static final int ENTRANCES = 1;
    private static final int SPAGHETTI = 2;
    private static final int PILLARS = 3;
    private static final int NOODLE = 4;
    private static final int COMPONENTS = 5;

    /** Registries of the running server, set when its levels are created. */
    private static volatile @Nullable RegistryAccess registries;

    private final DensityFunction[] functions;

    private NoiseCaves(DensityFunction[] functions) {
        this.functions = functions;
    }

    /** Called when a server creates its levels (and with null when it stops). */
    public static void setRegistries(@Nullable RegistryAccess access) {
        registries = access;
    }

    /** The cave functions, or null when the registries are not available (no server running). */
    static @Nullable NoiseCaves create() {
        RegistryAccess access = registries;
        if (access == null) {
            return null;
        }
        HolderGetter<DensityFunction> functions = access.lookupOrThrow(Registries.DENSITY_FUNCTION);
        HolderGetter<NormalNoise> noises = access.lookupOrThrow(Registries.NOISE);
        DensityFunction[] f = new DensityFunction[COMPONENTS];
        // NoiseRouterData.underground: layered caverns + cheese caverns (without the top slide, applied in Java)
        DensityFunction layers = DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_LAYER), 8.0).square().mul(4.0f);
        DensityFunction cheese = DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_CHEESE), 0.6666666666666666).add(0.27f).clamp(-1.0f, 1.0f);
        f[CAVERNS] = DensityFunctions.add(layers, cheese);
        f[ENTRANCES] = function(functions, "entrances");
        f[SPAGHETTI] = DensityFunctions.add(function(functions, "spaghetti_2d"), function(functions, "spaghetti_roughness_function"));
        DensityFunction pillars = function(functions, "pillars");
        f[PILLARS] = DensityFunctions.rangeChoice(pillars, -1000000.0f, 0.03f, DensityFunctions.constant(-1000000.0f), pillars);
        f[NOODLE] = function(functions, "noodle");
        return new NoiseCaves(f);
    }

    private static DensityFunction function(HolderGetter<DensityFunction> functions, String name) {
        return NoiseRouterData.getFunction(functions,
                ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.withDefaultNamespace("overworld/caves/" + name)));
    }

    /** Which columns of the chunk get noise caves, and how they are protected. */
    interface Columns {
        /** Terrain top of the column (world coordinates), or {@link TerrainSampler#NONE} for no terrain. */
        int terrainY(int x, int z);

        /** Whether the column may have noise caves (its biome's carver rule). */
        boolean enabled(int x, int z);

        /** Whether the column or a neighbor has map water or lava on top. */
        boolean nearFluid(int x, int z);
    }

    /** Digs the caves of one chunk. */
    void carve(ChunkAccess chunk, RandomState randomState, Columns columns) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinY();
        int[] terrain = new int[256];
        int highest = Integer.MIN_VALUE;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = pos.getBlockX(dx);
                int z = pos.getBlockZ(dz);
                int top = columns.enabled(x, z) ? columns.terrainY(x, z) : TerrainSampler.NONE;
                if (top != TerrainSampler.NONE && columns.nearFluid(x, z)) {
                    top -= FLUID_COVER;
                }
                terrain[dx * 16 + dz] = Math.min(top, chunk.getMaxY());
                highest = Math.max(highest, terrain[dx * 16 + dz]);
            }
        }
        if (highest <= minY + 1) {
            return;
        }

        // Component values at the cell corners, from the bottom of the world to the highest terrain.
        int cellsY = Math.floorDiv(highest - minY, CELL_Y) + 1;
        int cornersXz = 16 / CELL_XZ + 1;
        float[][] corners = new float[COMPONENTS][cornersXz * cornersXz * (cellsY + 1)];
        SamplerContext context = SamplerContext.builder().enableCaches().build();
        for (int c = 0; c < COMPONENTS; c++) {
            DensitySampler.Bound sampler = randomState.getSampler(this.functions[c]).bind(context);
            float[] values = corners[c];
            for (int cx = 0; cx < cornersXz; cx++) {
                for (int cz = 0; cz < cornersXz; cz++) {
                    for (int cy = 0; cy <= cellsY; cy++) {
                        values[(cx * cornersXz + cz) * (cellsY + 1) + cy] = sampler.sampleValue(pos.getMinBlockX() + cx * CELL_XZ,
                                minY + cy * CELL_Y, pos.getMinBlockZ() + cz * CELL_XZ);
                    }
                }
            }
        }

        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        float[] value = new float[COMPONENTS];
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int top = terrain[dx * 16 + dz];
                for (int y = minY + 1; y <= top; y++) {
                    for (int c = 0; c < COMPONENTS; c++) {
                        value[c] = interpolate(corners[c], cornersXz, cellsY, dx, y - minY, dz);
                    }
                    if (density(value, top - y) >= 0) {
                        continue;
                    }
                    blockPos.set(pos.getBlockX(dx), y, pos.getBlockZ(dz));
                    BlockState state = chunk.getBlockState(blockPos);
                    if (state.isAir() || state.is(BlockTags.UNCARVABLE) || !state.getFluidState().isEmpty()) {
                        continue;
                    }
                    chunk.setBlockState(blockPos, y < minY + LAVA_DEPTH ? Blocks.LAVA.defaultBlockState() : Blocks.CAVE_AIR.defaultBlockState());
                }
            }
        }
    }

    /**
     * Vanilla's cave density for the interpolated components at {@code depth} blocks below the
     * ground: negative is a cave.
     */
    static float density(float[] value, int depth) {
        float slopedCheese = depth / DEPTH_SCALE;
        float caves;
        if (slopedCheese < SURFACE_LIMIT) {
            caves = Math.min(slopedCheese, value[ENTRANCES] * 5.0f);
        } else {
            float slide = Mth.clamp(1.5f - 0.64f * slopedCheese, 0.0f, 0.5f);
            float underground = Math.min(Math.min(value[CAVERNS] + slide, value[ENTRANCES]), value[SPAGHETTI]);
            caves = Math.max(underground, value[PILLARS]);
        }
        return Math.min(caves, value[NOODLE]);
    }

    /** Trilinear interpolation of a corner grid at a block of the chunk ({@code ry} from the bottom of the world). */
    private static float interpolate(float[] values, int cornersXz, int cellsY, int dx, int ry, int dz) {
        int cx = dx / CELL_XZ;
        int cz = dz / CELL_XZ;
        int cy = ry / CELL_Y;
        float tx = (dx % CELL_XZ) / (float) CELL_XZ;
        float tz = (dz % CELL_XZ) / (float) CELL_XZ;
        float ty = (ry % CELL_Y) / (float) CELL_Y;
        int stride = cellsY + 1;
        int i000 = (cx * cornersXz + cz) * stride + cy;
        int i100 = ((cx + 1) * cornersXz + cz) * stride + cy;
        int i010 = (cx * cornersXz + cz + 1) * stride + cy;
        int i110 = ((cx + 1) * cornersXz + cz + 1) * stride + cy;
        return (float) Mth.lerp3(tx, ty, tz,
                values[i000], values[i100], values[i000 + 1], values[i100 + 1],
                values[i010], values[i110], values[i010 + 1], values[i110 + 1]);
    }
}
