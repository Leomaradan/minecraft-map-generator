package com.leomaradan.mapgen.world;

import com.leomaradan.mapgen.MapGen;
import com.leomaradan.mapgen.core.BiomeMapping;
import com.leomaradan.mapgen.core.BlockPalette;
import com.leomaradan.mapgen.core.MapConfig;
import com.leomaradan.mapgen.core.MapPack;
import com.leomaradan.mapgen.core.StructureEntry;
import com.leomaradan.mapgen.core.TerrainSampler;
import com.leomaradan.mapgen.mixin.CaveCarverConfigurationAccessor;
import com.leomaradan.mapgen.mixin.ChunkGeneratorStructureStateInvoker;
import com.leomaradan.mapgen.pack.MapPacks;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Builds the terrain from the map pack: the heightmap gives the ground, the water and lava maps
 * the fluid surfaces, the biome map the biomes. Decoration (features, carvers, structures) runs
 * on top as configured.
 */
public final class MapChunkGenerator extends ChunkGenerator {
    public static final MapCodec<MapChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            MapBiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.mapBiomes),
            MapSettings.CODEC.fieldOf("settings").forGetter(g -> g.settings)
    ).apply(i, i.stable(MapChunkGenerator::new)));

    /** Fallback ground for a world type selected without a ZIP (never used to create a world). */
    private static final int UNCONFIGURED_GROUND = 63;

    private final MapBiomeSource mapBiomes;
    private final MapSettings settings;
    private volatile Loaded loaded;
    /** Vanilla's cave functions, built once the server's registries are known. */
    private final AtomicReference<@Nullable NoiseCaves> noiseCaves = new AtomicReference<>();
    /** A noise generator with this world's height, only for the CarvingContext of the carvers. */
    private final AtomicReference<@Nullable NoiseBasedChunkGenerator> carvingGenerator = new AtomicReference<>();
    /** The biomes' carvers changed to only mark the carving mask (empty when the configuration is not a vanilla one). */
    private static final Map<ConfiguredWorldCarver<?>, Optional<ConfiguredWorldCarver<?>>> MASK_ONLY_CARVERS = Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * Pack data resolved on first use (the pack is only available once the server runs). The
     * per-slot lists of decoration have one more element at the end for the outside biome.
     *
     * @param carvers  carvers of each slot
     * @param features placed features allowed in each slot, null when biomes.json filters no feature
     */
    private record Loaded(MapPack pack, TerrainSampler sampler, List<Surface> surfaces, Surface outsideSurface, Palette defaultBlock,
                          Palette ceiling, boolean bedrock, MapStructures structures, List<MapConfig.Carvers> carvers, @Nullable List<Set<PlacedFeature>> features) {
        /** Index in the per-slot lists: the slot, or the last element for the outside biome. */
        int index(int slot) {
            return slot >= 0 && slot < this.surfaces.size() ? slot : this.surfaces.size();
        }
    }

    /** Surface blocks of one biome slot. */
    record Surface(Palette top, Palette filler, Palette underwater, int fillerDepth) {
    }

    /** Blocks of a layer, picked by position. */
    record Palette(@Nullable BlockPalette palette, List<BlockState> states) {
        static Palette of(BlockState state) {
            return new Palette(null, List.of(state));
        }

        BlockState at(int x, int y, int z) {
            return this.palette == null || this.states.size() == 1 ? this.states.getFirst() : this.states.get(this.palette.pick(x, y, z));
        }
    }

    public MapChunkGenerator(MapBiomeSource biomeSource, MapSettings settings) {
        super(biomeSource, generationSettings(settings));
        this.mapBiomes = biomeSource;
        this.settings = settings;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    /**
     * Biome features kept by the filters of map.json ({@code decoration.features}) and biomes.json.
     * With filters in biomes.json, a biome keeps the features any of its filters allows, and
     * {@link #allowsFeature} checks each position against the filter of its slot.
     */
    private static Function<Holder<Biome>, BiomeGenerationSettings> generationSettings(MapSettings settings) {
        MapConfig.Features filter = settings.featureFilter();
        if (settings.biomeFeatures().isEmpty()) {
            if (!filter.enabled()) {
                return biome -> BiomeGenerationSettings.EMPTY;
            }
            if (filter.isUnfiltered()) {
                return biome -> biome.value().getGenerationSettings();
            }
        }
        // Called for every biome of every chunk and by each feature's biome check: build once per biome.
        Map<Holder<Biome>, BiomeGenerationSettings> cache = new ConcurrentHashMap<>();
        return biome -> cache.computeIfAbsent(biome, b -> {
            String id = b.unwrapKey().map(key -> key.identifier().toString()).orElse("");
            List<MapConfig.Features> filters = new ArrayList<>();
            filters.add(filter);
            settings.biomeFeatures().stream().filter(f -> f.biome().equals(id)).forEach(f -> filters.add(f.filter()));
            return filtered(b.value().getGenerationSettings(), (feature, step) -> filters.stream().anyMatch(f -> f.allows(feature, step)));
        });
    }

    /** @param allows tests a feature id and its generation step */
    static BiomeGenerationSettings filtered(BiomeGenerationSettings settings, BiPredicate<String, String> allows) {
        BiomeGenerationSettings.PlainBuilder builder = new BiomeGenerationSettings.PlainBuilder();
        for (Holder<ConfiguredWorldCarver<?>> carver : settings.getCarvers()) {
            builder.addCarver(carver);
        }
        GenerationStep.Decoration[] steps = GenerationStep.Decoration.values();
        List<HolderSet<PlacedFeature>> features = settings.features();
        for (int step = 0; step < features.size(); step++) {
            String stepName = step < steps.length ? steps[step].getSerializedName() : "";
            for (Holder<PlacedFeature> feature : features.get(step)) {
                String id = feature.unwrapKey().map(key -> key.identifier().toString()).orElse("");
                if (allows.test(id, stepName)) {
                    builder.addFeature(step, feature);
                }
            }
        }
        return builder.build();
    }

    /** Placed features of a biome that a filter allows, compared by identity. */
    private static Set<PlacedFeature> allowedFeatures(Holder<Biome> biome, MapConfig.Features filter) {
        Set<PlacedFeature> allowed = Collections.newSetFromMap(new IdentityHashMap<>());
        if (filter.enabled()) {
            for (HolderSet<PlacedFeature> step : filtered(biome.value().getGenerationSettings(), filter::allows).features()) {
                step.forEach(feature -> allowed.add(feature.value()));
            }
        }
        return allowed;
    }

    public MapSettings settings() {
        return this.settings;
    }

    public MapBiomeSource mapBiomeSource() {
        return this.mapBiomes;
    }

    /** The map pack, or null for an unconfigured generator. */
    public @Nullable MapPack pack() {
        Loaded l = this.loaded();
        return l == null ? null : l.pack;
    }

    private @Nullable Loaded loaded() {
        if (!this.settings.isConfigured()) {
            return null;
        }
        Loaded l = this.loaded;
        if (l == null) {
            synchronized (this) {
                l = this.loaded;
                if (l == null) {
                    l = this.load(MapPacks.get(this.settings.pack(), this.settings.dimension()));
                    this.loaded = l;
                }
            }
        }
        return l;
    }

    private Loaded load(MapPack pack) {
        List<BiomeMapping.Variant> slots = pack.biomeMapping().slots();
        List<Surface> surfaces = new ArrayList<>();
        List<MapConfig.Carvers> carvers = new ArrayList<>();
        MapConfig.Features featureFilter = this.settings.featureFilter();
        List<Set<PlacedFeature>> features = pack.biomeMapping().hasFeatureOverrides() ? new ArrayList<>() : null;
        for (int i = 0; i < slots.size(); i++) {
            BiomeMapping.Variant slot = slots.get(i);
            surfaces.add(surfaceFor(this.mapBiomes.biomeForIndex(i), slot));
            carvers.add(slot.carvers() != null ? slot.carvers() : this.settings.carverFilter());
            if (features != null) {
                features.add(allowedFeatures(this.mapBiomes.biomeForIndex(i), slot.features() != null ? slot.features() : featureFilter));
            }
        }
        carvers.add(this.settings.carverFilter());
        if (features != null) {
            features.add(allowedFeatures(this.mapBiomes.outsideBiome(), featureFilter));
        }
        Surface outside = surfaceFor(this.mapBiomes.outsideBiome(), null);
        Palette defaultBlock = palette(pack.config().blocks().defaultBlock(), Blocks.STONE.defaultBlockState());
        Palette ceiling = palette(pack.config().blocks().ceiling(), Blocks.STONE.defaultBlockState());
        return new Loaded(pack, pack.sampler(), List.copyOf(surfaces), outside, defaultBlock, ceiling, pack.config().blocks().bedrock(),
                new MapStructures(pack),
                List.copyOf(carvers), features == null ? null : List.copyOf(features));
    }

    /** Surface blocks for a biome: explicit values from biomes.json, otherwise defaults from the biome tags. */
    static Surface surfaceFor(Holder<Biome> biome, BiomeMapping.@Nullable Variant entry) {
        BlockState top = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState filler = Blocks.DIRT.defaultBlockState();
        BlockState underwater = Blocks.DIRT.defaultBlockState();
        int depth = 3;
        if (biome.is(BiomeTags.IS_NETHER)) {
            top = filler = underwater = Blocks.NETHERRACK.defaultBlockState();
        } else if (biome.is(BiomeTags.IS_END)) {
            top = filler = underwater = Blocks.END_STONE.defaultBlockState();
        } else if (biome.is(BiomeTags.IS_BADLANDS)) {
            top = underwater = Blocks.RED_SAND.defaultBlockState();
            filler = Blocks.TERRACOTTA.defaultBlockState();
        } else if (biome.is(BiomeTags.IS_BEACH) || biome.is(Biomes.DESERT)) {
            top = underwater = Blocks.SAND.defaultBlockState();
            filler = Blocks.SANDSTONE.defaultBlockState();
        } else if (biome.is(BiomeTags.IS_OCEAN)) {
            top = underwater = Blocks.GRAVEL.defaultBlockState();
            filler = Blocks.GRAVEL.defaultBlockState();
        } else if (biome.is(BiomeTags.IS_RIVER)) {
            top = underwater = Blocks.SAND.defaultBlockState();
        } else if (biome.is(Biomes.MUSHROOM_FIELDS)) {
            top = Blocks.MYCELIUM.defaultBlockState();
        } else if (biome.is(Biomes.STONY_PEAKS) || biome.is(Biomes.STONY_SHORE)) {
            top = filler = underwater = Blocks.STONE.defaultBlockState();
        } else if (biome.is(Biomes.SNOWY_SLOPES) || biome.is(Biomes.FROZEN_PEAKS) || biome.is(Biomes.JAGGED_PEAKS)) {
            top = Blocks.SNOW_BLOCK.defaultBlockState();
            filler = Blocks.STONE.defaultBlockState();
        }
        if (entry == null) {
            return new Surface(Palette.of(top), Palette.of(filler), Palette.of(underwater), depth);
        }
        if (entry.fillerDepth() != null) {
            depth = entry.fillerDepth();
        }
        return new Surface(palette(entry.top(), top), palette(entry.filler(), filler), palette(entry.underwater(), underwater), depth);
    }

    /** Resolves the blocks of a palette; {@code fallback} when absent, and in place of unknown blocks. */
    static Palette palette(@Nullable BlockPalette palette, BlockState fallback) {
        if (palette == null) {
            return Palette.of(fallback);
        }
        return new Palette(palette, palette.ids().stream().map(id -> block(id, fallback)).toList());
    }

    static BlockState block(@Nullable String id, BlockState fallback) {
        if (id == null) {
            return fallback;
        }
        Identifier key = Identifier.tryParse(id);
        Optional<Block> block = key == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(key);
        if (block.isEmpty()) {
            MapGen.LOG.warn("Unknown block {} in the map pack, using {}", id, fallback);
            return fallback;
        }
        return block.get().defaultBlockState();
    }

    // ------------------------------------------------------------------------------------------
    // Terrain
    // ------------------------------------------------------------------------------------------

    /** Block at {@code y} for the sampled column at {@code x, z}, in a world from {@code minY} to {@code maxY}. */
    private BlockState stateAt(@Nullable Loaded l, TerrainSampler.Column column, int x, int y, int z, int minY, int maxY) {
        if (l != null && column.ceilingY != TerrainSampler.NONE && y >= column.ceilingY) {
            return y == maxY && l.bedrock ? Blocks.BEDROCK.defaultBlockState() : l.ceiling.at(x, y, z);
        }
        if (column.terrainY == TerrainSampler.NONE || (column.bottomY != TerrainSampler.NONE && y < column.bottomY)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (y > column.terrainY) {
            return fluidOrAir(column, y);
        }
        return l == null ? unconfiguredGround(column, y, minY) : groundState(l, column, x, y, z, minY);
    }

    /** Above the ground: the column's water or lava up to its surface, then air. */
    private static BlockState fluidOrAir(TerrainSampler.Column column, int y) {
        if (y <= column.fluidTop()) {
            return column.isLava() ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState();
        }
        return Blocks.AIR.defaultBlockState();
    }

    /** The ground of a map: bedrock at the bottom of the world, then the biome's surface layers over the default blocks. */
    private static BlockState groundState(Loaded l, TerrainSampler.Column column, int x, int y, int z, int minY) {
        if (y == minY && l.bedrock) {
            return Blocks.BEDROCK.defaultBlockState();
        }
        Surface surface = column.biome >= 0 && column.biome < l.surfaces.size() ? l.surfaces.get(column.biome) : l.outsideSurface;
        boolean wet = column.fluidTop() != TerrainSampler.NONE;
        if (y == column.terrainY) {
            return (wet ? surface.underwater : surface.top).at(x, y, z);
        }
        if (y >= column.terrainY - surface.fillerDepth) {
            return (wet ? surface.underwater : surface.filler).at(x, y, z);
        }
        return l.defaultBlock.at(x, y, z);
    }

    /** Ground of a generator without a map: grass, dirt, stone and bedrock. */
    private static BlockState unconfiguredGround(TerrainSampler.Column column, int y, int minY) {
        if (y == minY) {
            return Blocks.BEDROCK.defaultBlockState();
        }
        if (y == column.terrainY) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
        return y > column.terrainY - 4 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    private TerrainSampler.Column sample(@Nullable Loaded l, int x, int z, TerrainSampler.Column out) {
        if (l == null) {
            out.inside = false;
            out.terrainY = UNCONFIGURED_GROUND;
            out.waterY = TerrainSampler.NONE;
            out.lavaY = TerrainSampler.NONE;
            out.biome = -1;
            out.undergroundBiome = -1;
            return out;
        }
        return l.sampler.sample(x, z, out);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        Loaded l = this.loaded();
        this.fillTerrain(l, chunk);
        if (l != null && l.carvers.stream().anyMatch(MapConfig.Carvers::noise)) {
            NoiseCaves caves = this.noiseCaves();
            if (caves != null) {
                caves.carve(chunk, randomState, new ChunkColumns(l, chunk.getPos()));
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
        // The surface blocks are placed with the terrain, in fillFromNoise.
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk) {
        Loaded l = this.loaded();
        if (l != null ? l.carvers.stream().anyMatch(c -> c.cave() || c.canyon()) : this.settings.carverFilter().any()) {
            this.carve(l, chunk, region.registryAccess(), randomState, seed);
        }
    }

    private @Nullable NoiseCaves noiseCaves() {
        NoiseCaves caves = this.noiseCaves.get();
        if (caves == null) {
            caves = NoiseCaves.create();
            this.noiseCaves.set(caves);
        }
        return caves;
    }

    /** The columns of a chunk and its one-block border, sampled once for the noise caves. */
    private static final class ChunkColumns implements NoiseCaves.Columns {
        private static final int SIZE = 18;
        private final int originX;
        private final int originZ;
        private final int[] terrain = new int[SIZE * SIZE];
        private final boolean[] fluid = new boolean[SIZE * SIZE];
        private final boolean[] enabled = new boolean[SIZE * SIZE];
        private final boolean[] surface = new boolean[SIZE * SIZE];

        ChunkColumns(Loaded l, ChunkPos pos) {
            this.originX = pos.getMinBlockX() - 1;
            this.originZ = pos.getMinBlockZ() - 1;
            TerrainSampler.Column column = new TerrainSampler.Column();
            for (int i = 0; i < SIZE; i++) {
                for (int j = 0; j < SIZE; j++) {
                    int x = this.originX + i;
                    int z = this.originZ + j;
                    l.sampler.sample(x, z, column);
                    this.terrain[i * SIZE + j] = column.terrainY;
                    this.fluid[i * SIZE + j] = column.fluidTop() != TerrainSampler.NONE;
                    MapConfig.Carvers rule = l.carvers.get(l.index(column.biome));
                    this.enabled[i * SIZE + j] = rule.noise();
                    this.surface[i * SIZE + j] = rule.surface();
                }
            }
        }

        private int index(int x, int z) {
            return (x - this.originX) * SIZE + (z - this.originZ);
        }

        @Override
        public int terrainY(int x, int z) {
            return this.terrain[this.index(x, z)];
        }

        @Override
        public boolean enabled(int x, int z) {
            return this.enabled[this.index(x, z)];
        }

        @Override
        public boolean covered(int x, int z) {
            int i = this.index(x, z);
            return !this.surface[i] || this.fluid[i] || this.fluid[i - 1] || this.fluid[i + 1] || this.fluid[i - SIZE] || this.fluid[i + SIZE];
        }
    }

    private void fillTerrain(@Nullable Loaded l, ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        TerrainSampler.Column column = new TerrainSampler.Column();

        Set<LevelChunkSection> acquired = new HashSet<>();
        try {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    this.sample(l, pos.getBlockX(dx), pos.getBlockZ(dz), column);
                    if (column.terrainY != TerrainSampler.NONE || column.ceilingY != TerrainSampler.NONE) {
                        this.fillColumn(l, chunk, column, dx, dz, acquired);
                    }
                }
            }
        } finally {
            for (LevelChunkSection section : acquired) {
                section.release();
            }
        }
    }

    /** Places the blocks of one sampled column; {@code acquired} collects the sections to release. */
    private void fillColumn(@Nullable Loaded l, ChunkAccess chunk, TerrainSampler.Column column, int dx, int dz, Set<LevelChunkSection> acquired) {
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minY = chunk.getMinY();
        int x = chunk.getPos().getBlockX(dx);
        int z = chunk.getPos().getBlockZ(dz);
        // Not Math.clamp: the terrain can be above the world top (explicit dimension), which clamp rejects.
        int surface = Math.min(chunk.getMaxY(), Math.max(column.terrainY, column.fluidTop()));
        // A ceiling goes up to the top of the world; the generation heightmaps keep the floor under it.
        int top = column.ceilingY == TerrainSampler.NONE ? surface : chunk.getMaxY();
        int bottom = column.bottomY == TerrainSampler.NONE ? minY : Math.max(minY, column.bottomY);
        for (int y = bottom; y <= top; y++) {
            BlockState state = this.stateAt(l, column, x, y, z, minY, chunk.getMaxY());
            if (state.isAir()) {
                continue;
            }
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            if (acquired.add(section)) {
                section.acquire();
            }
            section.setBlockState(dx, SectionPos.sectionRelative(y), dz, state, false);
            if (y <= surface && (y >= surface - 1 || y == column.terrainY)) {
                oceanFloor.update(dx, y, dz, state);
                worldSurface.update(dx, y, dz, state);
            }
        }
    }

    /**
     * Runs the biomes' carvers (caves, ravines) the way the noise generator does, without
     * breaking into the water and lava placed by the map.
     * <p>
     * Before 26.3 the carvers write the blocks themselves. Here they run with nothing they may
     * replace, so they only mark the carving mask (as they do in 26.3), and
     * {@link #applyCarvingMask} writes the blocks.
     */
    private void carve(@Nullable Loaded l, ChunkAccess chunk, RegistryAccess registryAccess, RandomState randomState, long seed) {
        ChunkPos pos = chunk.getPos();
        LevelHeightAccessor heightAccessor = chunk.getHeightAccessorForGeneration();
        CarvingContext context = new CarvingContext(this.carvingGenerator(), registryAccess, heightAccessor, null, randomState, null);
        int minY = context.getMinGenY() + 1;
        int maxY = context.getMinGenY() + context.getGenDepth() - 1 - 7;
        CarvingMask mask = new CarvingMask(chunk.getHeight(), chunk.getMinY());
        // Never asked: the carvers don't replace any block.
        Aquifer aquifer = Aquifer.createDisabled((x, y, z) -> new Aquifer.FluidStatus(chunk.getMinY(), Blocks.AIR.defaultBlockState()));
        Function<BlockPos, Holder<Biome>> biomes = p -> this.mapBiomes.biomeAt(p.getX(), p.getY(), p.getZ());
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        CarverRun run = new CarverRun(chunk, context, biomes, aquifer, random, seed, mask);
        int range = 8;
        boolean carved = false;
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                carved |= this.carveFrom(l, new ChunkPos(pos.x() + dx, pos.z() + dz), run);
            }
        }
        if (carved) {
            applyCarvingMask(chunk, mask, minY, maxY, carvingTops(l, pos));
        }
    }

    /**
     * Highest block the carvers may dig in each column of the chunk: {@link NoiseCaves#COVER}
     * blocks under the ground where the column's carver rule keeps the caves closed on the surface.
     * Null when every column may open.
     */
    private static int @Nullable [] carvingTops(@Nullable Loaded l, ChunkPos pos) {
        if (l == null || l.carvers.stream().allMatch(MapConfig.Carvers::surface)) {
            return null;
        }
        int[] tops = new int[256];
        TerrainSampler.Column column = new TerrainSampler.Column();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                l.sampler.sample(pos.getBlockX(dx), pos.getBlockZ(dz), column);
                boolean open = column.terrainY == TerrainSampler.NONE || l.carvers.get(l.index(column.biome)).surface();
                tops[dx * 16 + dz] = open ? Integer.MAX_VALUE : column.terrainY - NoiseCaves.COVER;
            }
        }
        return tops;
    }

    /** What every carver of a chunk is run with. */
    private record CarverRun(ChunkAccess chunk, CarvingContext context, Function<BlockPos, Holder<Biome>> biomes, Aquifer aquifer, WorldgenRandom random,
                             long seed, CarvingMask mask) {
    }

    /**
     * Runs the carvers of the biome of {@code sourcePos} (where caves start) allowed by its carver
     * rule. Returns whether a carver ran.
     */
    private boolean carveFrom(@Nullable Loaded l, ChunkPos sourcePos, CarverRun run) {
        int x = sourcePos.getMinBlockX();
        int z = sourcePos.getMinBlockZ();
        MapConfig.Carvers rule = l != null ? l.carvers.get(l.index(l.sampler.biomeIndex(x, z))) : this.settings.carverFilter();
        int index = 0;
        boolean ran = false;
        for (Holder<ConfiguredWorldCarver<?>> carverHolder : this.mapBiomes.biomeAt(x, z).value().getGenerationSettings().getCarvers()) {
            ConfiguredWorldCarver<?> carver = carverHolder.value();
            // The seed depends on the index of the carver: skipped carvers still count.
            run.random().setLargeFeatureSeed(run.seed() + index, sourcePos.x(), sourcePos.z());
            Optional<ConfiguredWorldCarver<?>> maskOnly = maskOnly(carver);
            if (maskOnly.isPresent() && (carver.worldCarver() instanceof CanyonWorldCarver ? rule.canyon() : rule.cave())
                    && maskOnly.get().isStartChunk(run.random())) {
                maskOnly.get().carve(run.context(), run.chunk(), run.biomes(), run.random(), run.aquifer(), sourcePos, run.mask());
                ran = true;
            }
            index++;
        }
        return ran;
    }

    /**
     * The carver with an empty list of replaceable blocks: it marks the mask without touching the
     * chunk. Empty for a configuration type other than vanilla's cave and canyon ones (the carver is
     * skipped).
     */
    private static Optional<ConfiguredWorldCarver<?>> maskOnly(ConfiguredWorldCarver<?> carver) {
        return MASK_ONLY_CARVERS.computeIfAbsent(carver, c -> {
            CarverConfiguration config = c.config();
            CarverConfiguration base = new CarverConfiguration(config.probability, config.y, config.yScale, config.lavaLevel, config.debugSettings,
                    HolderSet.empty());
            if (config.getClass() == CaveCarverConfiguration.class) {
                CaveCarverConfiguration cave = (CaveCarverConfiguration) config;
                return Optional.of(withConfig(c, new CaveCarverConfiguration(base, cave.horizontalRadiusMultiplier, cave.verticalRadiusMultiplier,
                        ((CaveCarverConfigurationAccessor) cave).mapgen$floorLevel())));
            }
            if (config.getClass() == CanyonCarverConfiguration.class) {
                CanyonCarverConfiguration canyon = (CanyonCarverConfiguration) config;
                return Optional.of(withConfig(c, new CanyonCarverConfiguration(base, canyon.verticalRotation, canyon.shape)));
            }
            MapGen.LOG.warn("Carver {} has an unsupported configuration ({}): skipped", c.worldCarver(), config.getClass().getName());
            return Optional.empty();
        });
    }

    @SuppressWarnings("unchecked")
    private static <C extends CarverConfiguration> ConfiguredWorldCarver<?> withConfig(ConfiguredWorldCarver<?> carver, CarverConfiguration config) {
        return new ConfiguredWorldCarver<>((WorldCarver<C>) carver.worldCarver(), (C) config);
    }

    /**
     * CarvingContext needs a noise generator, but only reads its minimum Y and depth: one with this
     * world's height, built once.
     */
    private NoiseBasedChunkGenerator carvingGenerator() {
        NoiseBasedChunkGenerator generator = this.carvingGenerator.get();
        if (generator == null) {
            NoiseGeneratorSettings d = NoiseGeneratorSettings.dummy();
            NoiseGeneratorSettings noiseSettings = new NoiseGeneratorSettings(NoiseSettings.create(this.getMinY(), this.getGenDepth(), 1, 2), d.defaultBlock(),
                    d.defaultFluid(), d.noiseRouter(), d.surfaceRule(), d.spawnTarget(), d.seaLevel(), d.disableMobGeneration(), d.aquifersEnabled(),
                    d.oreVeinsEnabled(), d.useLegacyRandomSource());
            generator = new NoiseBasedChunkGenerator(this.biomeSource, Holder.direct(noiseSettings));
            this.carvingGenerator.set(generator);
        }
        return generator;
    }

    /**
     * Turns the carved blocks into cave air (lava near the bottom), except next to the map's fluids
     * and above {@code tops} (from {@link #carvingTops}).
     */
    private static void applyCarvingMask(ChunkAccess chunk, CarvingMask mask, int minY, int maxY, int @Nullable [] tops) {
        ChunkPos pos = chunk.getPos();
        int lavaLevel = chunk.getMinY() + 8;
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = pos.getBlockX(x);
                int worldZ = pos.getBlockZ(z);
                int top = tops == null ? maxY : Math.min(maxY, tops[x * 16 + z]);
                for (int y = top; y >= minY; y--) {
                    if (mask.get(x, y, z)) {
                        carveMaskedBlock(chunk, blockPos.set(worldX, y, worldZ), neighbor, lavaLevel);
                    }
                }
            }
        }
    }

    /** Carves one block of the mask, unless it is air, bedrock (26.3's uncarvable tag), a fluid or next to a fluid. */
    private static void carveMaskedBlock(ChunkAccess chunk, BlockPos pos, BlockPos.MutableBlockPos neighbor, int lavaLevel) {
        BlockState state = chunk.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.BEDROCK) || !state.getFluidState().isEmpty() || touchesFluid(chunk, pos, neighbor)) {
            return;
        }
        chunk.setBlockState(pos, pos.getY() <= lavaLevel ? Blocks.LAVA.defaultBlockState() : Blocks.CAVE_AIR.defaultBlockState());
    }

    /** True if a block above or beside (within the chunk) is a fluid: carving there would drain lakes. */
    private static boolean touchesFluid(ChunkAccess chunk, BlockPos pos, BlockPos.MutableBlockPos neighbor) {
        if (!chunk.getBlockState(neighbor.setWithOffset(pos, 0, 1, 0)).getFluidState().isEmpty()) {
            return true;
        }
        int lx = pos.getX() & 15;
        int lz = pos.getZ() & 15;
        return (lx > 0 && !chunk.getBlockState(neighbor.setWithOffset(pos, -1, 0, 0)).getFluidState().isEmpty())
                || (lx < 15 && !chunk.getBlockState(neighbor.setWithOffset(pos, 1, 0, 0)).getFluidState().isEmpty())
                || (lz > 0 && !chunk.getBlockState(neighbor.setWithOffset(pos, 0, 0, -1)).getFluidState().isEmpty())
                || (lz < 15 && !chunk.getBlockState(neighbor.setWithOffset(pos, 0, 0, 1)).getFluidState().isEmpty());
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
        Loaded l = this.loaded();
        TerrainSampler.Column column = this.sample(l, x, z, new TerrainSampler.Column());
        if (column.terrainY == TerrainSampler.NONE) {
            return heightAccessor.getMinY();
        }
        int minY = heightAccessor.getMinY();
        int maxY = heightAccessor.getMaxY();
        int fluidTop = column.fluidTop();
        if (fluidTop != TerrainSampler.NONE) {
            BlockState fluid = this.stateAt(l, column, x, Math.min(fluidTop, maxY), z, minY, maxY);
            if (type.isOpaque().test(fluid)) {
                return Math.min(fluidTop, maxY) + 1;
            }
        }
        for (int y = Math.min(column.terrainY, maxY); y >= minY; y--) {
            if (type.isOpaque().test(this.stateAt(l, column, x, y, z, minY, maxY))) {
                return y + 1;
            }
        }
        return minY;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
        Loaded l = this.loaded();
        TerrainSampler.Column column = this.sample(l, x, z, new TerrainSampler.Column());
        int minY = heightAccessor.getMinY();
        BlockState[] states = new BlockState[heightAccessor.getHeight()];
        for (int i = 0; i < states.length; i++) {
            states[i] = this.stateAt(l, column, x, minY + i, z, minY, heightAccessor.getMaxY());
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos) {
        Loaded l = this.loaded();
        if (l == null) {
            result.add("MapGen: no map selected");
            return;
        }
        TerrainSampler.Column column = l.sampler.sample(feetPos.getX(), feetPos.getZ(), new TerrainSampler.Column());
        result.add("MapGen: " + l.pack.name() + (column.inside ? "" : " (outside)") + " ground=" + column.terrainY
                + (column.fluidTop() != TerrainSampler.NONE ? " fluid=" + column.fluidTop() : ""));
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // Passive mobs from the biomes' spawn settings, like the noise generator.
        ChunkPos center = region.getCenter();
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        random.setDecorationSeed(region.getSeed(), center.getMinBlockX(), center.getMinBlockZ());
        Holder<Biome> biome = region.getBiome(center.getWorldPosition().atY(region.getMaxY()));
        NaturalSpawner.spawnMobsForChunkGeneration(region, biome, center, random);
    }

    @Override
    public int getGenDepth() {
        return this.settings.height();
    }

    @Override
    public int getSeaLevel() {
        return this.settings.seaLevel();
    }

    @Override
    public int getMinY() {
        return this.settings.minY();
    }

    // ------------------------------------------------------------------------------------------
    // Structures
    // ------------------------------------------------------------------------------------------

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets, RandomState randomState, long levelSeed) {
        MapConfig.VanillaStructures filter = this.settings.structureFilter();
        if (filter.equals(MapConfig.VanillaStructures.ALL)) {
            return super.createState(structureSets, randomState, levelSeed);
        }
        List<Holder<StructureSet>> sets = new ArrayList<>();
        if (filter.enabled()) {
            structureSets.listElements().forEach(holder -> {
                String setId = holder.key().identifier().toString();
                List<StructureSet.StructureSelectionEntry> kept = holder.value().structures().stream()
                        .filter(entry -> filter.allows(setId, entry.structure().unwrapKey().map(k -> k.identifier().toString()).orElse("")))
                        .toList();
                if (kept.isEmpty()) {
                    return;
                }
                Holder<StructureSet> set = kept.size() == holder.value().structures().size()
                        ? holder
                        : Holder.direct(new StructureSet(kept, holder.value().placement()));
                if (hasBiomes(set.value())) {
                    sets.add(set);
                }
            });
        }
        return ChunkGeneratorStructureStateInvoker.mapgen$create(randomState, this.biomeSource, levelSeed, levelSeed, List.copyOf(sets));
    }

    private boolean hasBiomes(StructureSet set) {
        Set<Holder<Biome>> possible = this.biomeSource.possibleBiomes();
        return set.structures().stream().anyMatch(entry -> entry.structure().value().biomes().stream().anyMatch(possible::contains));
    }

    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureManager structureManager,
                                 ChunkAccess centerChunk, StructureTemplateManager structureTemplateManager, ResourceKey<Level> level) {
        super.createStructures(registryAccess, state, structureManager, centerChunk, structureTemplateManager, level);
        Loaded l = this.loaded();
        if (l == null) {
            return;
        }
        ChunkPos chunkPos = centerChunk.getPos();
        HolderLookup.RegistryLookup<Structure> structures = registryAccess.lookupOrThrow(Registries.STRUCTURE);
        for (StructureEntry.Structure entry : l.structures.forcedIn(chunkPos)) {
            Optional<Holder.Reference<Structure>> holder = resolve(entry, structures);
            if (holder.isEmpty()) {
                MapGen.LOG.warn("[{}] Unknown structure {} (and no existing fallback_id) at {}, {}: skipped", l.pack.name(), entry.id(), entry.x(),
                        entry.z());
                continue;
            }
            Structure structure = holder.get().value();
            StructureStart existing = structureManager.getStartForStructure(SectionPos.bottomOf(centerChunk), structure, centerChunk);
            if (existing != null && existing.isValid()) {
                continue;
            }
            StructureStart start = this.generateWithRetries(l, entry, holder.get(), new StructureContext(registryAccess, state, structureTemplateManager, level),
                    centerChunk);
            if (start.isValid()) {
                // Stored in the entry's chunk even when generated from a neighbor: references follow the pieces' bounds.
                structureManager.setStartForStructure(SectionPos.bottomOf(centerChunk), structure, start, centerChunk);
            }
        }
    }

    /** What createStructures receives to generate structures. */
    private record StructureContext(RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureTemplateManager templateManager,
                                    ResourceKey<Level> level) {
    }

    /** Generates a structure of structures.json, trying other seeds and nearby chunks when it fails. */
    private StructureStart generateWithRetries(Loaded l, StructureEntry.Structure entry, Holder<Structure> holder, StructureContext context,
                                               ChunkAccess centerChunk) {
        ChunkPos chunkPos = centerChunk.getPos();
        ChunkGeneratorStructureState state = context.state();
        List<StructureAttempt> attempts = StructureAttempt.list(entry.retries());
        for (int i = 0; i < attempts.size(); i++) {
            StructureAttempt attempt = attempts.get(i);
            ChunkPos source = new ChunkPos(chunkPos.x() + attempt.dx(), chunkPos.z() + attempt.dz());
            StructureStart start = holder.value().generate(holder, context.level(), context.registryAccess(), this, this.biomeSource, state.randomState(), context.templateManager(), state.getLevelSeed() + attempt.seedOffset(), source, 0, centerChunk, biome -> true);
            if (start.isValid()) {
                if (i > 0) {
                    MapGen.LOG.info("[{}] Structure {} at {}, {} generated on retry {} (moved by {}, {} chunks)", l.pack.name(), entry.id(),
                            entry.x(), entry.z(), i, attempt.dx(), attempt.dz());
                }
                return start;
            }
        }
        MapGen.LOG.warn("[{}] Structure {} could not be generated at {}, {} ({} attempts)", l.pack.name(), entry.id(), entry.x(), entry.z(),
                attempts.size());
        return StructureStart.INVALID_START;
    }

    /**
     * One try at generating a structure of structures.json: a chunk offset from the entry's chunk
     * and an offset added to the world seed (another layout, rotation or jigsaw choice).
     */
    record StructureAttempt(int dx, int dz, long seedOffset) {
        private static final long SEED_STEP = 0x9E3779B97F4A7C15L;
        private static final int SAME_CHUNK_SEEDS = 3;
        private static final int MAX_RING = 2;

        /**
         * The first try, then up to {@code retries} more: other seeds in the same chunk, the
         * chunks around (nearest ring first) with the world seed, then the same chunks with
         * another seed.
         */
        static List<StructureAttempt> list(int retries) {
            List<StructureAttempt> attempts = new ArrayList<>();
            attempts.add(new StructureAttempt(0, 0, 0));
            for (int s = 1; s <= SAME_CHUNK_SEEDS; s++) {
                attempts.add(new StructureAttempt(0, 0, s * SEED_STEP));
            }
            List<int[]> neighbors = new ArrayList<>();
            for (int ring = 1; ring <= MAX_RING; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                            neighbors.add(new int[]{dx, dz});
                        }
                    }
                }
            }
            for (long seed : new long[]{0, SEED_STEP}) {
                for (int[] n : neighbors) {
                    attempts.add(new StructureAttempt(n[0], n[1], seed));
                }
            }
            return List.copyOf(attempts.subList(0, Math.min(attempts.size(), retries + 1)));
        }
    }

    /** The structure of an entry: its {@code id}, else its {@code fallback_id}; empty when neither exists. */
    private static Optional<Holder.Reference<Structure>> resolve(StructureEntry.Structure entry, HolderLookup<Structure> structures) {
        String id = entry.resolve(candidate -> structureHolder(candidate, structures).isPresent());
        return id == null ? Optional.empty() : structureHolder(id, structures);
    }

    private static Optional<Holder.Reference<Structure>> structureHolder(String id, HolderLookup<Structure> structures) {
        Identifier key = Identifier.tryParse(id);
        return key == null ? Optional.empty() : structures.get(ResourceKey.create(Registries.STRUCTURE, key));
    }

    /** Makes the structures listed in structures.json findable with /locate. */
    @Override
    public @Nullable Pair<BlockPos, Holder<Structure>> findNearestMapStructure(ServerLevel level, HolderSet<Structure> wantedStructures, BlockPos pos,
                                                                                int maxSearchRadius, boolean createReference) {
        Loaded l = this.loaded();
        int radius = vanillaSearchRadius(l, level, wantedStructures, pos, maxSearchRadius);
        Pair<BlockPos, Holder<Structure>> nearest = super.findNearestMapStructure(level, wantedStructures, pos, radius, createReference);
        if (l == null || !level.getServer().getWorldGenSettings().options().generateStructures()) {
            return nearest;
        }
        double nearestDistance = nearest == null ? Double.MAX_VALUE : pos.distSqr(nearest.getFirst());
        HolderLookup<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (StructureEntry.Structure entry : l.structures.forced()) {
            Optional<Holder.Reference<Structure>> resolved = resolve(entry, structures);
            if (resolved.isEmpty()) {
                continue;
            }
            for (Holder<Structure> wanted : wantedStructures) {
                if (wanted.is(resolved.get().key())) {
                    BlockPos candidate = new BlockPos(entry.x(), this.surfaceY(entry.x(), entry.z()), entry.z());
                    double distance = pos.distSqr(candidate);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = Pair.of(candidate, wanted);
                    }
                }
            }
        }
        return nearest;
    }

    /**
     * Vanilla's search radius (in rings of the placement's spacing), limited to the map and a margin
     * of one map size around it. Before 26.3, vanilla reads the region file of every candidate chunk
     * (creating it when missing): around a map where no candidate is valid, such as an ocean outside,
     * the full search blocks the server and leaves thousands of empty region files.
     */
    private static int vanillaSearchRadius(@Nullable Loaded l, ServerLevel level, HolderSet<Structure> wantedStructures, BlockPos pos,
                                           int maxSearchRadius) {
        if (l == null) {
            return maxSearchRadius;
        }
        TerrainSampler s = l.sampler;
        int margin = Math.max(s.endX() - s.originX(), s.endZ() - s.originZ());
        int dx = Math.max(Math.abs(pos.getX() - s.originX()), Math.abs(pos.getX() - s.endX()));
        int dz = Math.max(Math.abs(pos.getZ() - s.originZ()), Math.abs(pos.getZ() - s.endZ()));
        int chunks = SectionPos.blockToSectionCoord(Math.max(dx, dz) + margin) + 1;
        int spacing = Integer.MAX_VALUE;
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        for (Holder<Structure> structure : wantedStructures) {
            for (StructurePlacement placement : state.getPlacementsForStructure(structure)) {
                if (placement instanceof RandomSpreadStructurePlacement spread) {
                    spacing = Math.min(spacing, spread.spacing());
                }
            }
        }
        return spacing == Integer.MAX_VALUE ? maxSearchRadius : Math.min(maxSearchRadius, Math.ceilDiv(chunks, spacing));
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        super.applyBiomeDecoration(level, chunk, structureManager);
        Loaded l = this.loaded();
        if (l != null) {
            l.structures.placeTemplates(level, chunk, this);
        }
    }

    /**
     * True if a placed feature may be placed at a position: checks the feature filter of the slot
     * there, when biomes.json has feature filters (called by the {@code biome} placement filter).
     */
    public boolean allowsFeature(BlockPos pos, PlacedFeature feature) {
        Loaded l = this.loaded();
        if (l == null || l.features == null) {
            return true;
        }
        return l.features.get(l.index(l.sampler.biomeIndex(pos.getX(), pos.getY(), pos.getZ()))).contains(feature);
    }

    /** True if snow and ice may form at a position ({@code snow} of map.json and biomes.json). */
    public boolean allowsSnow(BlockPos pos) {
        Loaded l = this.loaded();
        return l == null || l.sampler.allowsSnow(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Spawn point from map.json, or null to let vanilla choose. */
    public @Nullable BlockPos configuredSpawn(LevelHeightAccessor heightAccessor) {
        Loaded l = this.loaded();
        if (l == null || l.pack.config().spawn() == null) {
            return null;
        }
        MapConfig.Spawn spawn = l.pack.config().spawn();
        return this.spawnAt(spawn.x(), spawn.y(), spawn.z(), heightAccessor);
    }

    /**
     * Puts the world border around the map ({@code outside.border}). The border is a square: it
     * fits the longest side, centered on the map.
     *
     * @return false when the border is disabled or no map is selected
     */
    public boolean configureWorldBorder(WorldBorder border) {
        Loaded l = this.loaded();
        if (l == null || !l.pack.config().outside().border()) {
            return false;
        }
        TerrainSampler s = l.sampler;
        border.setCenter((s.originX() + s.endX()) / 2.0, (s.originZ() + s.endZ()) / 2.0);
        border.setSize(Math.max(s.endX() - s.originX(), s.endZ() - s.originZ()));
        return true;
    }

    /** Surface at the center of the map, or null when no map is selected. */
    public @Nullable BlockPos mapCenterSpawn(LevelHeightAccessor heightAccessor) {
        Loaded l = this.loaded();
        if (l == null) {
            return null;
        }
        TerrainSampler s = l.sampler;
        return this.spawnAt((s.originX() + s.endX()) / 2, null, (s.originZ() + s.endZ()) / 2, heightAccessor);
    }

    private BlockPos spawnAt(int x, @Nullable Integer y, int z, LevelHeightAccessor heightAccessor) {
        int spawnY = y != null ? y : this.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING, heightAccessor, null);
        return new BlockPos(x, Math.clamp(spawnY, heightAccessor.getMinY() + 1, heightAccessor.getMaxY()), z);
    }

    /** Surface Y (first free block above the ground) used to place templates on the surface. */
    int surfaceY(int x, int z) {
        Loaded l = this.loaded();
        TerrainSampler.Column column = this.sample(l, x, z, new TerrainSampler.Column());
        return column.terrainY == TerrainSampler.NONE ? this.getMinY() : column.terrainY + 1;
    }

    static RandomSource templateRandom(long seed, StructureEntry.Template entry) {
        return RandomSource.create(seed ^ (((long) entry.x()) << 32) ^ entry.z() ^ entry.path().hashCode());
    }
}
