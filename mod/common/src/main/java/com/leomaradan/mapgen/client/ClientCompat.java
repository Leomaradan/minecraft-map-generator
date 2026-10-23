package com.leomaradan.mapgen.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Client methods that moved from {@link Minecraft} to {@link Gui} in 26.2, looked up at runtime so
 * one source builds for 26.1 and 26.2.
 */
public final class ClientCompat {
    /** {@code Gui.setScreen} in 26.2, {@code Minecraft.setScreen} in 26.1: takes the Minecraft instance. */
    private static final MethodHandle SET_SCREEN = find("setScreen", "setScreen", MethodType.methodType(void.class, Screen.class));
    /** {@code Gui.toastManager()} in 26.2, {@code Minecraft.getToastManager()} in 26.1: takes the Minecraft instance. */
    private static final MethodHandle TOAST_MANAGER = find("toastManager", "getToastManager", MethodType.methodType(ToastManager.class));

    private ClientCompat() {
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        try {
            SET_SCREEN.invoke(minecraft, screen);
        } catch (Throwable t) {
            throw new IllegalStateException("Can't set the screen", t);
        }
    }

    public static ToastManager toastManager(Minecraft minecraft) {
        try {
            return (ToastManager) TOAST_MANAGER.invoke(minecraft);
        } catch (Throwable t) {
            throw new IllegalStateException("Can't get the toast manager", t);
        }
    }

    /** The method on {@code minecraft.gui} (26.2), or on {@code minecraft} itself (26.1). */
    private static MethodHandle find(String guiName, String minecraftName, MethodType type) {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            MethodHandle onGui = lookup.findVirtual(Gui.class, guiName, type);
            MethodHandle gui = lookup.findGetter(Minecraft.class, "gui", Gui.class);
            return MethodHandles.filterArguments(onGui, 0, gui);
        } catch (NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
            // 26.1: the method is on Minecraft.
        }
        try {
            return lookup.findVirtual(Minecraft.class, minecraftName, type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("No " + guiName + " on Gui nor " + minecraftName + " on Minecraft", e);
        }
    }
}
