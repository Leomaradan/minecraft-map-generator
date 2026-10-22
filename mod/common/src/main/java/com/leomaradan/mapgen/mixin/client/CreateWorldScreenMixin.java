package com.leomaradan.mapgen.mixin.client;

import com.leomaradan.mapgen.client.ClientMaps;
import com.leomaradan.mapgen.client.SelectMapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void mapgen$reset(CallbackInfo ci) {
        CreateWorldScreen screen = (CreateWorldScreen) (Object) this;
        ClientMaps.reset(screen);
        screen.getUiState().addListener(ClientMaps::onUiChanged);
    }

    /** "Create New World" with the map world type but no ZIP yet: ask for the ZIP instead. */
    @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
    private void mapgen$requireMap(CallbackInfo ci) {
        CreateWorldScreen screen = (CreateWorldScreen) (Object) this;
        WorldCreationUiState state = screen.getUiState();
        if (ClientMaps.needsSelection(state)) {
            ci.cancel();
            Minecraft minecraft = Minecraft.getInstance();
            SystemToast.add(minecraft.gui.toastManager(), SystemToast.SystemToastId.PACK_LOAD_FAILURE, Component.translatable("mapgen.error.title"),
                    Component.translatable("mapgen.error.no_map"));
            minecraft.gui.setScreen(new SelectMapScreen(screen, state.getSettings()));
        }
    }
}
