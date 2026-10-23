package com.leomaradan.mapgen.mixin;

import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code floorLevel} is package-private in 26.1 (public in 26.2). */
@Mixin(CaveCarverConfiguration.class)
public interface CaveCarverConfigurationAccessor {

    @Accessor("floorLevel")
    FloatProvider mapgen$floorLevel();
}
