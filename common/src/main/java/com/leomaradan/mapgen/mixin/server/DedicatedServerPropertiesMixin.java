package com.leomaradan.mapgen.mixin.server;

import com.leomaradan.mapgen.server.DedicatedServerMaps;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DedicatedServerProperties.class)
public abstract class DedicatedServerPropertiesMixin {

    @Inject(method = "createDimensions", at = @At("RETURN"), cancellable = true)
    private void mapgen$configureMap(HolderLookup.Provider registries, CallbackInfoReturnable<WorldDimensions> cir) {
        cir.setReturnValue(DedicatedServerMaps.configure(cir.getReturnValue(), registries));
    }
}
