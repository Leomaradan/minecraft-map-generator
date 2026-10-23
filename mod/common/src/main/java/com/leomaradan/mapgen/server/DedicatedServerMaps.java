package com.leomaradan.mapgen.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leomaradan.mapgen.MapGen;
import com.leomaradan.mapgen.core.MapArchive;
import com.leomaradan.mapgen.core.MapPackReader;
import com.leomaradan.mapgen.core.ValidationReport;
import com.leomaradan.mapgen.pack.MapPacks;
import com.leomaradan.mapgen.world.MapChunkGenerator;
import com.leomaradan.mapgen.world.MapWorlds;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dedicated server support: with {@code level-type=mapgen:map} and
 * {@code generator-settings={"pack":"maps/my_map.zip"}} in server.properties, a new world is
 * generated from that ZIP.
 */
public final class DedicatedServerMaps {
    private static final AtomicReference<@Nullable MapArchive> PENDING = new AtomicReference<>();

    private DedicatedServerMaps() {
    }

    /**
     * Called before the server loads its datapacks, when the world folder has no level.dat yet:
     * validates the ZIP, copies it into the world and installs its datapacks.
     */
    public static void prepareNewWorld(Path worldPath) {
        Path worldDir = worldPath.toAbsolutePath().normalize();
        Properties properties = new Properties();
        Path file = Path.of("server.properties");
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            } catch (IOException e) {
                MapGen.LOG.warn("Can't read server.properties", e);
                return;
            }
        }
        String levelType = properties.getProperty("level-type", "").trim().toLowerCase(Locale.ROOT);
        if (!levelType.equals(MapGen.PRESET.identifier().toString())) {
            return;
        }
        String packPath = packPath(properties.getProperty("generator-settings", ""));
        if (packPath == null) {
            throw new IllegalStateException("level-type is " + levelType + " but generator-settings does not name the map ZIP. "
                    + "Set generator-settings={\"pack\":\"path/to/map.zip\"} in server.properties");
        }
        Path zip = Path.of(packPath);
        MapPackReader.Result result = MapPackReader.read(zip);
        if (!result.ok()) {
            throw new IllegalStateException("The map ZIP " + zip.toAbsolutePath() + " is invalid:\n" + result.report());
        }
        MapArchive pack = result.archive();
        result.report().warnings().forEach(w -> MapGen.LOG.warn("[{}] {}", pack.name(), w));
        MapGen.LOG.info("Creating world {} from map {} ({})", worldDir, pack.name(), pack.summary());
        MapPacks.register(pack);
        MapPacks.copyInto(pack, worldDir);
        try {
            MapWorlds.writeDatapacks(pack, worldDir.resolve("datapacks"));
        } catch (IOException e) {
            throw new IllegalStateException("Can't install the datapacks of " + zip + ": " + e.getMessage(), e);
        }
        PENDING.set(pack);
    }

    private static @Nullable String packPath(String generatorSettings) {
        String text = generatorSettings.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (!text.startsWith("{")) {
            // Shorthand: generator-settings=maps/my_map.zip
            return text;
        }
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        return json.has("pack") ? json.get("pack").getAsString() : null;
    }

    /** Puts the generators of the pending ZIP's maps in the dimensions of the new world (the preset's placeholder overworld). */
    public static WorldDimensions configure(WorldDimensions dimensions, Registry<LevelStem> datapackDimensions, HolderLookup.Provider registries) {
        MapArchive pack = PENDING.get();
        if (pack == null || !(dimensions.overworld() instanceof MapChunkGenerator)) {
            return dimensions;
        }
        ValidationReport report = new ValidationReport();
        MapWorlds.validateRegistries(pack, registries.lookupOrThrow(Registries.STRUCTURE), report);
        WorldDimensions configured = MapWorlds.configureDimensions(pack, dimensions, datapackDimensions, registries, report);
        if (configured == null || report.hasErrors()) {
            throw new IllegalStateException("The map " + pack.name() + " can't be used:\n" + report);
        }
        report.warnings().forEach(w -> MapGen.LOG.warn("[{}] {}", pack.name(), w));
        PENDING.set(null);
        return configured;
    }
}
