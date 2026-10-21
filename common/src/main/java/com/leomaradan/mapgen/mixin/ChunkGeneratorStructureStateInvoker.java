package com.leomaradan.mapgen.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Builds a structure state from a filtered list of structure sets while keeping vanilla's ring seed. */
@Mixin(ChunkGeneratorStructureState.class)
public interface ChunkGeneratorStructureStateInvoker {

    @Invoker("<init>")
    static ChunkGeneratorStructureState mapgen$create(RandomState randomState, BiomeSource biomeSource, long levelSeed, ChunkPos origin,
                                                      long concentricRingsSeed, List<Holder<StructureSet>> possibleStructureSets) {
        throw new AssertionError();
    }
}
