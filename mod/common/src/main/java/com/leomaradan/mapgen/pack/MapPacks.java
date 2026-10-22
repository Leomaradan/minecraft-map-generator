package com.leomaradan.mapgen.pack;

import com.leomaradan.mapgen.MapGen;
import com.leomaradan.mapgen.core.MapPack;
import com.leomaradan.mapgen.core.MapPackReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the map pack of a world from its id.
 * <p>
 * Worlds only store the pack id in level.dat; the ZIP itself is copied into the world folder
 * ({@code <world>/mapgen/<id>.zip}) so the world stays self-contained. Packs selected in the
 * Create World screen are registered here before the world folder exists, and copied when the
 * server starts.
 */
public final class MapPacks {
    public static final String WORLD_FOLDER = "mapgen";

    private static final Map<String, MapPack> LOADED = new ConcurrentHashMap<>();
    private static volatile Path worldDirectory;

    private MapPacks() {
    }

    /** Makes a pack known (selected in the UI, or read by the dedicated server at startup). */
    public static void register(MapPack pack) {
        LOADED.put(pack.id(), pack);
    }

    /**
     * Called when a server starts: remembers its world folder and copies every pack its
     * dimensions use into it.
     */
    public static void onServerStarting(Path worldDir, Iterable<String> usedPackIds) {
        worldDirectory = worldDir;
        for (String id : usedPackIds) {
            if (id.isEmpty()) {
                continue;
            }
            Path target = packPath(worldDir, id);
            if (Files.isRegularFile(target)) {
                continue;
            }
            MapPack pack = LOADED.get(id);
            if (pack == null) {
                throw new IllegalStateException("The map pack " + id + " of this world is missing: expected " + target);
            }
            copyInto(pack, worldDir);
        }
    }

    public static void onServerStopped() {
        worldDirectory = null;
        // Keep the cache small: rasters can be large.
        LOADED.clear();
    }

    public static Path packPath(Path worldDir, String id) {
        return worldDir.resolve(WORLD_FOLDER).resolve(id + ".zip");
    }

    public static void copyInto(MapPack pack, Path worldDir) {
        Path target = packPath(worldDir, pack.id());
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(pack.source(), tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            MapGen.LOG.info("Copied map pack {} ({}) into {}", pack.name(), pack.id(), target);
        } catch (IOException e) {
            throw new IllegalStateException("Can't copy the map pack " + pack.source() + " into the world: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the pack with the given id, reading it from the current world folder if needed.
     *
     * @throws IllegalStateException if it can't be found or read
     */
    public static MapPack get(String id) {
        MapPack pack = LOADED.get(id);
        if (pack != null) {
            return pack;
        }
        synchronized (MapPacks.class) {
            pack = LOADED.get(id);
            if (pack != null) {
                return pack;
            }
            Path worldDir = worldDirectory;
            if (worldDir == null) {
                throw new IllegalStateException("Map pack " + id + " requested outside of a running world");
            }
            Path zip = packPath(worldDir, id);
            MapPackReader.Result result = MapPackReader.read(zip);
            if (!result.ok()) {
                throw new IllegalStateException("Can't load the map pack " + zip + ":\n" + result.report());
            }
            pack = result.pack();
            if (!pack.id().equals(id)) {
                MapGen.LOG.warn("Map pack {} has changed since the world was created (id {} instead of {})", zip, pack.id(), id);
            }
            for (String warning : result.report().warnings()) {
                MapGen.LOG.warn("[{}] {}", pack.name(), warning);
            }
            LOADED.put(id, pack);
            return pack;
        }
    }
}
