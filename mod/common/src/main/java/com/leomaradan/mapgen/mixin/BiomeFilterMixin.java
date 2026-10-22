package com.leomaradan.mapgen.mixin;

import com.leomaradan.mapgen.world.MapChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the {@code features} filters of biomes.json. The biome check only knows the biome at a
 * position; two entries of biomes.json can use the same biome with different filters.
 */
@Mixin(BiomeFilter.class)
public abstract class BiomeFilterMixin {

    @Inject(method = "shouldPlace", at = @At("RETURN"), cancellable = true)
    private void mapgen$filterBySlot(PlacementContext context, RandomSource random, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && context.generator() instanceof MapChunkGenerator generator
                && context.topFeature().filter(feature -> !generator.allowsFeature(pos, feature)).isPresent()) {
            cir.setReturnValue(false);
        }
    }
}
