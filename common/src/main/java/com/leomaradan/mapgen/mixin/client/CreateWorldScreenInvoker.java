package com.leomaradan.mapgen.mixin.client;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Reuses the Create World screen's own datapack handling (temporary folder, reload). */
@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenInvoker {

    @Invoker("getDataPackSelectionSettings")
    Pair<Path, PackRepository> mapgen$getDataPackSelectionSettings(WorldDataConfiguration dataConfiguration);

    @Invoker("tryApplyNewDataPacks")
    void mapgen$tryApplyNewDataPacks(PackRepository packRepository, boolean isDataPackScreen, Consumer<WorldDataConfiguration> onAbort);
}
