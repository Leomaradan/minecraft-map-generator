package com.leomaradan.mapgen.fabric;

import com.leomaradan.mapgen.MapGen;
import com.leomaradan.mapgen.world.MapBiomeSource;
import com.leomaradan.mapgen.world.MapChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class MapGenFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, MapGen.id("map"), MapChunkGenerator.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, MapGen.id("map"), MapBiomeSource.CODEC);
        MapGen.LOG.info("{} loaded on Fabric", MapGen.MOD_NAME);
    }
}
