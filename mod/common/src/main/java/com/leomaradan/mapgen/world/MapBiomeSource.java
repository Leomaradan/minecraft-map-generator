package com.leomaradan.mapgen.world;

import com.leomaradan.mapgen.core.MapPack;
import com.leomaradan.mapgen.core.TerrainSampler;
import com.leomaradan.mapgen.pack.MapPacks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.List;
import java.util.stream.Stream;

/**
 * Biomes read from the biome map. {@code biomes} is aligned with the slots of the pack's biome
 * mapping (one holder per mapped color, then one per substitute biome), so the biome ids are saved
 * in level.dat and resolved against the registry like any other worldgen setting.
 */
public final class MapBiomeSource extends BiomeSource {
    public static final MapCodec<MapBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.optionalFieldOf("pack", "").forGetter(s -> s.packId),
            Biome.CODEC.listOf().optionalFieldOf("biomes", List.of()).forGetter(s -> s.biomes),
            Biome.CODEC.fieldOf("outside").forGetter(s -> s.outside)
    ).apply(i, i.stable(MapBiomeSource::new)));

    private final String packId;
    private final List<Holder<Biome>> biomes;
    private final Holder<Biome> outside;
    private volatile TerrainSampler sampler;

    public MapBiomeSource(String packId, List<Holder<Biome>> biomes, Holder<Biome> outside) {
        this.packId = packId;
        this.biomes = List.copyOf(biomes);
        this.outside = outside;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.concat(this.biomes.stream(), Stream.of(this.outside));
    }

    @Override
    public BiomeResolver createResolver(Climate.Sampler sampler) {
        return (quartX, quartY, quartZ) -> this.biomeAt(QuartPos.toBlock(quartX), QuartPos.toBlock(quartY), QuartPos.toBlock(quartZ));
    }

    /** Used for one chunk on one thread: the columns are sampled once and reused for every Y. */
    @Override
    public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ, int maxQuartX, int maxQuartY,
                                                int maxQuartZ) {
        TerrainSampler s = this.sampler();
        if (s == null) {
            return (quartX, quartY, quartZ) -> this.outside;
        }
        TerrainSampler.Column[] columns = new TerrainSampler.Column[16];
        long[] keys = new long[16];
        return (quartX, quartY, quartZ) -> {
            int slot = (quartX & 3) | (quartZ & 3) << 2;
            long key = ((long) quartX << 32) | (quartZ & 0xFFFFFFFFL);
            TerrainSampler.Column column = columns[slot];
            if (column == null || keys[slot] != key) {
                column = s.sample(QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ), column == null ? new TerrainSampler.Column() : column);
                columns[slot] = column;
                keys[slot] = key;
            }
            return this.biomeForIndex(column.biomeAt(QuartPos.toBlock(quartY)));
        };
    }

    /** Biome at the surface of a block position. */
    public Holder<Biome> biomeAt(int blockX, int blockZ) {
        TerrainSampler s = this.sampler();
        if (s == null) {
            return this.outside;
        }
        return this.biomeForIndex(s.biomeIndex(blockX, blockZ));
    }

    /** Biome at a block position (substitute underground biomes vary with Y). */
    public Holder<Biome> biomeAt(int blockX, int blockY, int blockZ) {
        TerrainSampler s = this.sampler();
        if (s == null) {
            return this.outside;
        }
        return this.biomeForIndex(s.biomeIndex(blockX, blockY, blockZ));
    }

    public Holder<Biome> biomeForIndex(int index) {
        return index >= 0 && index < this.biomes.size() ? this.biomes.get(index) : this.outside;
    }

    public List<Holder<Biome>> mappedBiomes() {
        return this.biomes;
    }

    public Holder<Biome> outsideBiome() {
        return this.outside;
    }

    private TerrainSampler sampler() {
        if (this.packId.isEmpty()) {
            return null;
        }
        TerrainSampler s = this.sampler;
        if (s == null) {
            MapPack pack = MapPacks.get(this.packId);
            s = pack.sampler();
            this.sampler = s;
        }
        return s;
    }

    @Override
    public void addDebugInfo(List<String> result, BlockPos feetPos, Climate.Sampler sampler) {
        result.add("MapGen biome slot: " + (this.packId.isEmpty() ? "-" : this.sampler().biomeIndex(feetPos.getX(), feetPos.getY(), feetPos.getZ())));
    }
}
