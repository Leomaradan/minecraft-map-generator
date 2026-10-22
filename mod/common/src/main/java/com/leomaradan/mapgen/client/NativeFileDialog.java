package com.leomaradan.mapgen.client;

import com.leomaradan.mapgen.MapGen;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.Consumer;

/** The operating system's "open file" dialog, through SDL3 (which Minecraft uses for its window). */
final class NativeFileDialog {
    private NativeFileDialog() {
    }

    /**
     * Opens the dialog; {@code onPicked} runs on the render thread with the chosen file. Nothing
     * happens when the user cancels; {@code onUnavailable} runs if the system has no file dialog.
     */
    static void openZip(Path initialDirectory, Consumer<Path> onPicked, Runnable onUnavailable) {
        Minecraft minecraft = Minecraft.getInstance();
        ByteBuffer name = MemoryUtil.memUTF8("Map ZIP");
        ByteBuffer pattern = MemoryUtil.memUTF8("zip");
        SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.malloc(1);
        filters.get(0).name(name).pattern(pattern);
        SDL_DialogFileCallback[] callback = new SDL_DialogFileCallback[1];
        Runnable free = () -> {
            MemoryUtil.memFree(name);
            MemoryUtil.memFree(pattern);
            filters.free();
            if (callback[0] != null) {
                callback[0].free();
            }
        };
        try {
            // SDL may call back from another thread, and the filters must stay valid until then.
            callback[0] = SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
                String picked = null;
                boolean failed = fileList == 0L;
                if (failed) {
                    MapGen.LOG.warn("File dialog unavailable: {}", SDLError.SDL_GetError());
                } else {
                    long first = MemoryUtil.memGetAddress(fileList);
                    if (first != 0L) {
                        picked = MemoryUtil.memUTF8(first);
                    }
                }
                String result = picked;
                minecraft.execute(() -> {
                    free.run();
                    if (failed) {
                        onUnavailable.run();
                    } else if (result != null) {
                        onPicked.accept(Path.of(result));
                    }
                });
            });
            SDLDialog.SDL_ShowOpenFileDialog(callback[0], 0L, minecraft.getWindow().handle(), filters, initialDirectory.toAbsolutePath().toString(),
                    false);
        } catch (Throwable t) {
            MapGen.LOG.warn("Can't open the file dialog", t);
            free.run();
            onUnavailable.run();
        }
    }
}
