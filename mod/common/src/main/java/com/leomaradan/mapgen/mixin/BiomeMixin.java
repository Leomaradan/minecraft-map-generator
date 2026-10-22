package com.leomaradan.mapgen.mixin;

import com.leomaradan.mapgen.world.MapChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies {@code snow} of map.json and biomes.json. Both the {@code freeze_top_layer} feature
 * (world generation) and the weather go through these checks.
 */
@Mixin(Biome.class)
public abstract class BiomeMixin {

    @Inject(method = "shouldSnow", at = @At("HEAD"), cancellable = true)
    private void mapgen$limitSnow(LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!mapgen$allowsSnow(level, pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z", at = @At("HEAD"), cancellable = true)
    private void mapgen$limitIce(LevelReader level, BlockPos pos, boolean checkNeighbors, CallbackInfoReturnable<Boolean> cir) {
        if (!mapgen$allowsSnow(level, pos)) {
            cir.setReturnValue(false);
        }
    }

    private static boolean mapgen$allowsSnow(LevelReader level, BlockPos pos) {
        return !(level instanceof ServerLevelAccessor server && server.getLevel().getChunkSource().getGenerator() instanceof MapChunkGenerator generator)
                || generator.allowsSnow(pos);
    }
}
