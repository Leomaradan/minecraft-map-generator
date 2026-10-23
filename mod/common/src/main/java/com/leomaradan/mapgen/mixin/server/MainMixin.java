package com.leomaradan.mapgen.mixin.server;

import com.leomaradan.mapgen.server.DedicatedServerMaps;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.server.Main;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Main.class)
public abstract class MainMixin {

    /** A new world is about to be created: install the map ZIP and its datapacks before they are loaded. */
    @WrapOperation(method = "main", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;hasWorldData()Z"))
    private static boolean mapgen$prepareNewWorld(LevelStorageSource.LevelStorageAccess access, Operation<Boolean> original) {
        boolean hasWorldData = original.call(access);
        if (!hasWorldData) {
            DedicatedServerMaps.prepareNewWorld(access.getLevelPath(LevelResource.ROOT));
        }
        return hasWorldData;
    }

    /** The dimensions of a new world: put the map's generators in them (it needs the dimensions of datapacks and mods). */
    @WrapOperation(method = "createNewWorldData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/dedicated/DedicatedServerProperties;createDimensions(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/level/levelgen/WorldDimensions;"))
    private static WorldDimensions mapgen$configureMap(DedicatedServerProperties properties, HolderLookup.Provider registries,
                                                       Operation<WorldDimensions> original, @Local(argsOnly = true) Registry<LevelStem> datapackDimensions) {
        return DedicatedServerMaps.configure(original.call(properties, registries), datapackDimensions, registries);
    }
}
