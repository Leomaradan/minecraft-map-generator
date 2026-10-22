package com.leomaradan.mapgen.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leomaradan.mapgen.MapGen;
import com.leomaradan.mapgen.core.MapPack;
import com.leomaradan.mapgen.core.MapPackReader;
import com.leomaradan.mapgen.core.ValidationReport;
import com.leomaradan.mapgen.pack.MapPacks;
import com.leomaradan.mapgen.world.MapChunkGenerator;
import com.leomaradan.mapgen.world.MapWorlds;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Dedicated server support: with {@code level-type=mapgen:map} and
 * {@code generator-settings={"pack":"maps/my_map.zip"}} in server.properties, a new world is
 * generated from that ZIP.
 */
public final class DedicatedServerMaps {
    private static volatile @Nullable MapPack pending;

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
        MapPack pack = result.pack();
        result.report().warnings().forEach(w -> MapGen.LOG.warn("[{}] {}", pack.name(), w));
        MapGen.LOG.info("Creating world {} from map {} ({})", worldDir, pack.name(), pack.summary());
        MapPacks.register(pack);
        MapPacks.copyInto(pack, worldDir);
        try {
            MapWorlds.writeDatapacks(pack, worldDir.resolve("datapacks"));
        } catch (IOException e) {
            throw new IllegalStateException("Can't install the datapacks of " + zip + ": " + e.getMessage(), e);
        }
        pending = pack;
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

    /** Replaces the placeholder overworld of the preset with the generator for the pending pack. */
    public static WorldDimensions configure(WorldDimensions dimensions, HolderLookup.Provider registries) {
        MapPack pack = pending;
        if (pack == null || !(dimensions.overworld() instanceof MapChunkGenerator)) {
            return dimensions;
        }
        ValidationReport report = new ValidationReport();
        MapWorlds.validateRegistries(pack, registries.lookupOrThrow(Registries.STRUCTURE), report);
        MapChunkGenerator generator = MapWorlds.createGenerator(pack, registries.lookupOrThrow(Registries.BIOME), report);
        if (generator == null || report.hasErrors()) {
            throw new IllegalStateException("The map " + pack.name() + " can't be used:\n" + report);
        }
        pending = null;
        return dimensions.replaceOverworldGenerator(registries, generator);
    }
}
