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
 * Biomes read from the biome map. {@code biomes} is aligned with the entries of the pack's biome
 * mapping (one holder per mapped color), so the biome ids are saved in level.dat and resolved
 * against the registry like any other worldgen setting.
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
        return (quartX, quartY, quartZ) -> this.biomeAt(QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ));
    }

    /** Biome at a block position (biomes don't vary with Y). */
    public Holder<Biome> biomeAt(int blockX, int blockZ) {
        TerrainSampler s = this.sampler();
        if (s == null) {
            return this.outside;
        }
        return this.biomeForIndex(s.biomeIndex(blockX, blockZ));
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
        result.add("MapGen biome index: " + (this.packId.isEmpty() ? "-" : this.sampler().biomeIndex(feetPos.getX(), feetPos.getZ())));
    }
}
