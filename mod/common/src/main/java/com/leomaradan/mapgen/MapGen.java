package com.leomaradan.mapgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MapGen {
    public static final String MOD_ID = "mapgen";
    public static final String MOD_NAME = "Map Generator";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    /** The "Map from ZIP" world type. */
    public static final ResourceKey<WorldPreset> PRESET = ResourceKey.create(Registries.WORLD_PRESET, id("map"));

    private MapGen() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
