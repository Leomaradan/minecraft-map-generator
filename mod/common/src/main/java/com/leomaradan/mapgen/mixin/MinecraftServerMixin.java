package com.leomaradan.mapgen.mixin;

import com.leomaradan.mapgen.pack.MapPacks;
import com.leomaradan.mapgen.world.MapChunkGenerator;
import com.leomaradan.mapgen.world.NoiseCaves;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    /** Before the levels exist: copy the map packs into the world and load them (fails early on a bad pack). */
    @Inject(method = "createLevels", at = @At("HEAD"))
    private void mapgen$installPacks(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        NoiseCaves.setRegistries(server.registryAccess());
        List<String> packIds = new ArrayList<>();
        server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).listElements().forEach(stem -> {
            if (stem.value().generator() instanceof MapChunkGenerator generator && generator.settings().isConfigured()) {
                packIds.add(generator.settings().pack());
            }
        });
        if (packIds.isEmpty()) {
            return;
        }
        MapPacks.onServerStarting(server.getWorldPath(LevelResource.ROOT), packIds);
        packIds.forEach(MapPacks::get);
    }

    /**
     * When the world is created: puts the world border around the map, and applies the spawn
     * point of map.json once vanilla has picked one. Without a spawn in map.json, a vanilla spawn
     * outside the border moves to the center of the map.
     */
    @Inject(method = "setInitialSpawn", at = @At("TAIL"))
    private static void mapgen$setSpawn(ServerLevel level, ServerLevelData levelData, boolean spawnBonusChest, boolean isDebug,
                                        LevelLoadListener levelLoadListener, CallbackInfo ci) {
        if (level.getChunkSource().getGenerator() instanceof MapChunkGenerator generator) {
            boolean border = generator.configureWorldBorder(level.getWorldBorder());
            BlockPos spawn = generator.configuredSpawn(level);
            if (spawn == null && border && !level.getWorldBorder().isWithinBounds(levelData.getRespawnData().pos())) {
                spawn = generator.mapCenterSpawn(level);
            }
            if (spawn != null) {
                levelData.setSpawn(LevelData.RespawnData.of(level.dimension(), spawn, 0.0f, 0.0f));
            }
        }
    }

    @Inject(method = "stopServer", at = @At("TAIL"))
    private void mapgen$forgetPacks(CallbackInfo ci) {
        MapPacks.onServerStopped();
        NoiseCaves.setRegistries(null);
    }
}
