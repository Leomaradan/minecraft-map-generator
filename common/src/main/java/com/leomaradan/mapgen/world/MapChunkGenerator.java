package com.leomaradan.mapgen.world;

import com.leomaradan.mapgen.MapGen;
import com.leomaradan.mapgen.core.BiomeMapping;
import com.leomaradan.mapgen.core.MapConfig;
import com.leomaradan.mapgen.core.MapPack;
import com.leomaradan.mapgen.core.StructureEntry;
import com.leomaradan.mapgen.core.TerrainSampler;
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
import net.minecraft.tags.BlockTags;
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
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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

    /** Pack data resolved on first use (the pack is only available once the server runs). */
    private record Loaded(MapPack pack, TerrainSampler sampler, Surface[] surfaces, Surface outsideSurface, BlockState defaultBlock,
                          boolean bedrock, MapStructures structures) {
    }

    /** Surface blocks of one biome mapping entry. */
    record Surface(BlockState top, BlockState filler, BlockState underwater, int fillerDepth) {
    }

    public MapChunkGenerator(MapBiomeSource biomeSource, MapSettings settings) {
        super(biomeSource, biome -> settings.features() ? biome.value().getGenerationSettings() : BiomeGenerationSettings.EMPTY);
        this.mapBiomes = biomeSource;
        this.settings = settings;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
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
                    l = this.load(MapPacks.get(this.settings.pack()));
                    this.loaded = l;
                }
            }
        }
        return l;
    }

    private Loaded load(MapPack pack) {
        List<BiomeMapping.Entry> entries = pack.biomeMapping().entries();
        Surface[] surfaces = new Surface[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            surfaces[i] = surfaceFor(this.mapBiomes.biomeForIndex(i), entries.get(i));
        }
        Surface outside = surfaceFor(this.mapBiomes.outsideBiome(), null);
        BlockState defaultBlock = block(pack.config().blocks().defaultBlock(), Blocks.STONE.defaultBlockState());
        return new Loaded(pack, pack.sampler(), surfaces, outside, defaultBlock, pack.config().blocks().bedrock(), new MapStructures(pack));
    }

    /** Surface blocks for a biome: explicit values from biomes.json, otherwise defaults from the biome tags. */
    static Surface surfaceFor(Holder<Biome> biome, BiomeMapping.@Nullable Entry entry) {
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
        if (entry != null) {
            top = block(entry.top(), top);
            filler = block(entry.filler(), filler);
            underwater = block(entry.underwater(), underwater);
            if (entry.fillerDepth() != null) {
                depth = entry.fillerDepth();
            }
        }
        return new Surface(top, filler, underwater, depth);
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

    /** Block at {@code y} for a sampled column. */
    private BlockState stateAt(@Nullable Loaded l, TerrainSampler.Column column, int y, int minY) {
        if (column.terrainY == TerrainSampler.NONE) {
            return Blocks.AIR.defaultBlockState();
        }
        int fluidTop = column.fluidTop();
        if (y > column.terrainY) {
            if (y <= fluidTop) {
                return column.isLava() ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        }
        if (l == null) {
            if (y == minY) {
                return Blocks.BEDROCK.defaultBlockState();
            }
            return y == column.terrainY ? Blocks.GRASS_BLOCK.defaultBlockState() : y > column.terrainY - 4 ? Blocks.DIRT.defaultBlockState()
                    : Blocks.STONE.defaultBlockState();
        }
        if (y == minY && l.bedrock) {
            return Blocks.BEDROCK.defaultBlockState();
        }
        Surface surface = column.biome >= 0 && column.biome < l.surfaces.length ? l.surfaces[column.biome] : l.outsideSurface;
        if (y == column.terrainY) {
            return fluidTop != TerrainSampler.NONE ? surface.underwater : surface.top;
        }
        if (y >= column.terrainY - surface.fillerDepth) {
            return fluidTop != TerrainSampler.NONE ? surface.underwater : surface.filler;
        }
        return l.defaultBlock;
    }

    private TerrainSampler.Column sample(@Nullable Loaded l, int x, int z, TerrainSampler.Column out) {
        if (l == null) {
            out.inside = false;
            out.terrainY = UNCONFIGURED_GROUND;
            out.waterY = TerrainSampler.NONE;
            out.lavaY = TerrainSampler.NONE;
            out.biome = -1;
            return out;
        }
        return l.sampler.sample(x, z, out);
    }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState, StructureManager structureManager,
                                                       BiomeManager biomeManager, @Nullable WorldGenRegion carverBiomeRegion,
                                                       Set<Holder<Biome>> possibleBiomes) {
        Loaded l = this.loaded();
        this.fillTerrain(l, chunk);
        if (this.settings.carvers()) {
            this.carve(chunk, randomState, carverBiomeRegion);
        }
        return CompletableFuture.completedFuture(chunk);
    }

    private void fillTerrain(@Nullable Loaded l, ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        TerrainSampler.Column column = new TerrainSampler.Column();

        Set<LevelChunkSection> acquired = new HashSet<>();
        try {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    this.sample(l, pos.getBlockX(dx), pos.getBlockZ(dz), column);
                    if (column.terrainY == TerrainSampler.NONE) {
                        continue;
                    }
                    int top = Math.min(maxY, Math.max(column.terrainY, column.fluidTop()));
                    for (int y = minY; y <= top; y++) {
                        BlockState state = this.stateAt(l, column, y, minY);
                        if (state.isAir()) {
                            continue;
                        }
                        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                        if (acquired.add(section)) {
                            section.acquire();
                        }
                        section.setBlockState(dx, SectionPos.sectionRelative(y), dz, state, false);
                        if (y >= top - 1 || y == column.terrainY) {
                            oceanFloor.update(dx, y, dz, state);
                            worldSurface.update(dx, y, dz, state);
                        }
                    }
                }
            }
        } finally {
            for (LevelChunkSection section : acquired) {
                section.release();
            }
        }
    }

    /**
     * Runs the biomes' carvers (caves, ravines) the way the noise generator does, without
     * breaking into the water and lava placed by the map.
     */
    private void carve(ChunkAccess chunk, RandomState randomState, @Nullable WorldGenRegion carverBiomeRegion) {
        ChunkPos pos = chunk.getPos();
        WorldGenerationContext context = new WorldGenerationContext(this, chunk.getHeightAccessorForGeneration());
        int maxY = context.getMinGenY() + context.getGenDepth() - 1 - 7;
        CarvingMask mask = new CarvingMask(context.getMinGenY() + 1, maxY);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        int range = 8;
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                ChunkPos sourcePos = new ChunkPos(pos.x() + dx, pos.z() + dz);
                Holder<Biome> biome = this.mapBiomes.biomeAt(sourcePos.getMinBlockX(), sourcePos.getMinBlockZ());
                BiomeGenerationSettings biomeSettings = biome.value().getGenerationSettings();
                int index = 0;
                for (Holder<WorldCarver> carverHolder : biomeSettings.getCarvers()) {
                    WorldCarver carver = carverHolder.value();
                    random.setLargeFeatureSeed(randomState.seed() + index, sourcePos.x(), sourcePos.z());
                    if (carver.isStartChunk(random)) {
                        carver.carve(context, random, pos, sourcePos, mask);
                    }
                    index++;
                }
            }
        }
        if (mask.isEmpty()) {
            return;
        }
        int lavaLevel = chunk.getMinY() + 8;
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        mask.visit((x, z, bottomY, topY) -> {
            int worldX = pos.getBlockX(x);
            int worldZ = pos.getBlockZ(z);
            for (int y = topY; y >= bottomY; y--) {
                blockPos.set(worldX, y, worldZ);
                BlockState state = chunk.getBlockState(blockPos);
                if (state.isAir() || state.is(BlockTags.UNCARVABLE) || !state.getFluidState().isEmpty()) {
                    continue;
                }
                if (touchesFluid(chunk, blockPos, neighbor)) {
                    continue;
                }
                chunk.setBlockState(blockPos, y <= lavaLevel ? Blocks.LAVA.defaultBlockState() : Blocks.CAVE_AIR.defaultBlockState());
            }
        });
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
            BlockState fluid = this.stateAt(l, column, Math.min(fluidTop, maxY), minY);
            if (type.isOpaque().test(fluid)) {
                return Math.min(fluidTop, maxY) + 1;
            }
        }
        for (int y = Math.min(column.terrainY, maxY); y >= minY; y--) {
            if (type.isOpaque().test(this.stateAt(l, column, y, minY))) {
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
            states[i] = this.stateAt(l, column, minY + i, minY);
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
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
        NaturalSpawner.spawnMobsForChunkGeneration(region, center.getWorldPosition().atY(region.getMaxY()), center, random);
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
        return ChunkGeneratorStructureStateInvoker.mapgen$create(randomState, this.biomeSource, levelSeed, this.getOrigin(randomState), levelSeed,
                List.copyOf(sets));
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
            Identifier id = Identifier.tryParse(entry.id());
            Optional<Holder.Reference<Structure>> holder = id == null ? Optional.empty() : structures.get(ResourceKey.create(Registries.STRUCTURE, id));
            if (holder.isEmpty()) {
                MapGen.LOG.warn("[{}] Unknown structure {} at {}, {}", l.pack.name(), entry.id(), entry.x(), entry.z());
                continue;
            }
            Structure structure = holder.get().value();
            StructureStart existing = structureManager.getStartForStructure(structure, centerChunk);
            if (existing != null && existing.isValid()) {
                continue;
            }
            Climate.Sampler climate = state.randomState().createClimateSampler(SamplerContext.builder().enableCaches().build());
            StructureStart start = structure.generate(holder.get(), level, registryAccess, this, this.biomeSource, climate, state.randomState(),
                    structureTemplateManager, state.getLevelSeed(), chunkPos, 0, centerChunk, biome -> true);
            if (start.isValid()) {
                structureManager.setStartForStructure(structure, start, centerChunk);
            } else {
                MapGen.LOG.warn("[{}] Structure {} could not be generated at {}, {}", l.pack.name(), entry.id(), entry.x(), entry.z());
            }
        }
    }

    /** Makes the structures listed in structures.json findable with /locate. */
    @Override
    public @Nullable Pair<BlockPos, Holder<Structure>> findNearestMapStructure(ServerLevel level, HolderSet<Structure> wantedStructures, BlockPos pos,
                                                                                int maxSearchRadius, boolean createReference) {
        Pair<BlockPos, Holder<Structure>> nearest = super.findNearestMapStructure(level, wantedStructures, pos, maxSearchRadius, createReference);
        Loaded l = this.loaded();
        if (l == null || !level.getServer().getWorldGenSettings().options().generateStructures()) {
            return nearest;
        }
        double nearestDistance = nearest == null ? Double.MAX_VALUE : pos.distSqr(nearest.getFirst());
        for (StructureEntry.Structure entry : l.structures.forced()) {
            for (Holder<Structure> wanted : wantedStructures) {
                if (wanted.unwrapKey().map(key -> key.identifier().toString().equals(entry.id())).orElse(false)) {
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

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        super.applyBiomeDecoration(level, chunk, structureManager);
        Loaded l = this.loaded();
        if (l != null) {
            l.structures.placeTemplates(level, chunk, this);
        }
    }

    /** Spawn point from map.json, or null to let vanilla choose. */
    public @Nullable BlockPos configuredSpawn(LevelHeightAccessor heightAccessor) {
        Loaded l = this.loaded();
        if (l == null || l.pack.config().spawn() == null) {
            return null;
        }
        MapConfig.Spawn spawn = l.pack.config().spawn();
        int y;
        if (spawn.y() != null) {
            y = spawn.y();
        } else {
            y = this.getBaseHeight(spawn.x(), spawn.z(), Heightmap.Types.MOTION_BLOCKING, heightAccessor, null);
        }
        return new BlockPos(spawn.x(), Math.clamp(y, heightAccessor.getMinY() + 1, heightAccessor.getMaxY()), spawn.z());
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
