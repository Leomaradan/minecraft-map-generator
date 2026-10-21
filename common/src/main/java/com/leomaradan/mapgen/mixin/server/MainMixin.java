package com.leomaradan.mapgen.mixin.server;

import com.leomaradan.mapgen.server.DedicatedServerMaps;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
}
