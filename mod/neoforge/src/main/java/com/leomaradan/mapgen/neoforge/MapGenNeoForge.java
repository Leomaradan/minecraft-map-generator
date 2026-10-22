package com.leomaradan.mapgen.neoforge;

import com.leomaradan.mapgen.MapGen;
import com.leomaradan.mapgen.world.MapBiomeSource;
import com.leomaradan.mapgen.world.MapChunkGenerator;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(MapGen.MOD_ID)
public final class MapGenNeoForge {
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, MapGen.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, MapGen.MOD_ID);

    static {
        CHUNK_GENERATORS.register("map", () -> MapChunkGenerator.CODEC);
        BIOME_SOURCES.register("map", () -> MapBiomeSource.CODEC);
    }

    public MapGenNeoForge(IEventBus modBus) {
        CHUNK_GENERATORS.register(modBus);
        BIOME_SOURCES.register(modBus);
        MapGen.LOG.info("{} loaded on NeoForge", MapGen.MOD_NAME);
    }
}
