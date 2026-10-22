package com.leomaradan.mapgen.neoforge;

import com.leomaradan.mapgen.platform.IPlatformHelper;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public Path getGameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isDedicatedServer() {
        return FMLEnvironment.getDist().isDedicatedServer();
    }
}
