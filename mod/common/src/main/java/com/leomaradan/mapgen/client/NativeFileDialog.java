package com.leomaradan.mapgen.client;

import com.leomaradan.mapgen.MapGen;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The operating system's "open file" dialog, through tinyfiledialogs (bundled with Minecraft before
 * 26.3, which uses GLFW for its window).
 */
final class NativeFileDialog {
    private NativeFileDialog() {
    }

    /**
     * Opens the dialog; {@code onPicked} runs on the render thread with the chosen file. Nothing
     * happens when the user cancels; {@code onUnavailable} runs if the dialog can't be opened.
     */
    static void openZip(Path initialDirectory, Consumer<Path> onPicked, Runnable onUnavailable) {
        Minecraft minecraft = Minecraft.getInstance();
        // The dialog blocks until it closes: keep it off the render thread.
        Thread thread = new Thread(() -> {
            String picked;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer patterns = stack.mallocPointer(1);
                patterns.put(stack.UTF8("*.zip")).flip();
                // A trailing separator makes tinyfd open inside the folder.
                picked = TinyFileDialogs.tinyfd_openFileDialog("", initialDirectory.toAbsolutePath() + File.separator, patterns, "Map ZIP", false);
            } catch (Throwable t) {
                MapGen.LOG.warn("Can't open the file dialog", t);
                minecraft.execute(onUnavailable);
                return;
            }
            if (picked != null) {
                minecraft.execute(() -> onPicked.accept(Path.of(picked)));
            }
        }, "MapGen file dialog");
        thread.setDaemon(true);
        thread.start();
    }
}
