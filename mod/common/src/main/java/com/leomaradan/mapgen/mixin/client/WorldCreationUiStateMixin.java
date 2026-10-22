package com.leomaradan.mapgen.mixin.client;

import com.leomaradan.mapgen.client.ClientMaps;
import com.leomaradan.mapgen.client.SelectMapScreen;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldCreationUiState.class)
public abstract class WorldCreationUiStateMixin {

    /** The "Customize" button of the map world type opens the ZIP selection screen. */
    @Inject(method = "getPresetEditor", at = @At("RETURN"), cancellable = true)
    private void mapgen$presetEditor(CallbackInfoReturnable<PresetEditor> cir) {
        if (cir.getReturnValue() == null && ClientMaps.isMapPreset((WorldCreationUiState) (Object) this)) {
            cir.setReturnValue(SelectMapScreen::new);
        }
    }

    @Inject(method = "setSettings", at = @At("TAIL"))
    private void mapgen$settingsReplaced(WorldCreationContext settings, CallbackInfo ci) {
        ClientMaps.onSettingsReplaced((WorldCreationUiState) (Object) this);
    }
}
