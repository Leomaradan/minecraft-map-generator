package com.leomaradan.mapgen.world;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leomaradan.mapgen.MapGen;
import com.leomaradan.mapgen.core.BiomeMapping;
import com.leomaradan.mapgen.core.DimensionLayout;
import com.leomaradan.mapgen.core.MapPack;
import com.leomaradan.mapgen.core.StructureEntry;
import com.leomaradan.mapgen.core.ValidationReport;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Turns a map pack into world settings: generator, datapacks. */
public final class MapWorlds {
    private static final String DIMENSION_TYPE_TEMPLATE = "/mapgen/overworld_dimension_type.json";

    private MapWorlds() {
    }

    /**
     * Builds the overworld generator for a pack, checking that every biome it names exists.
     *
     * @return null if a biome is missing (the reasons are added to {@code report})
     */
    public static @Nullable MapChunkGenerator createGenerator(MapPack pack, HolderGetter<Biome> biomes, ValidationReport report) {
        List<Holder<Biome>> mapped = new ArrayList<>();
        for (BiomeMapping.Variant slot : pack.biomeMapping().slots()) {
            Holder<Biome> biome = biome(biomes, slot.biome());
            if (biome == null) {
                report.error("biome " + slot.biome() + " (" + slot.origin()
                        + ") does not exist; add it with the ZIP datapack or install the mod that provides it");
            } else {
                mapped.add(biome);
            }
        }
        Holder<Biome> outside = biome(biomes, pack.config().outside().biome());
        if (outside == null) {
            report.error("outside biome " + pack.config().outside().biome() + " does not exist");
        }
        if (report.hasErrors()) {
            return null;
        }
        return new MapChunkGenerator(new MapBiomeSource(pack.id(), mapped, outside), MapSettings.of(pack));
    }

    /** Extra checks needing registries: blocks and structures named by the pack. */
    public static void validateRegistries(MapPack pack, HolderGetter<Structure> structures, ValidationReport report) {
        for (String block : pack.blockIds()) {
            if (BuiltInRegistries.BLOCK.getOptional(Identifier.parse(block)).isEmpty()) {
                report.error("block " + block + " does not exist");
            }
        }
        for (StructureEntry entry : pack.structures()) {
            if (entry instanceof StructureEntry.Structure structure
                    && structures.get(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(structure.id()))).isEmpty()) {
                report.error("structure " + structure.id() + " (at " + structure.x() + ", " + structure.z() + ") does not exist");
            }
        }
    }

    private static @Nullable Holder<Biome> biome(HolderGetter<Biome> biomes, String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) {
            return null;
        }
        Optional<? extends Holder<Biome>> holder = biomes.get(ResourceKey.create(Registries.BIOME, key));
        return holder.orElse(null);
    }

    /** Name of the folder of the datapack holding the ZIP's own datapack. */
    public static String embeddedPackName(MapPack pack) {
        return "mapgen_" + pack.id() + "_datapack";
    }

    /** Name of the folder of the generated datapack resizing the overworld. */
    public static String dimensionPackName(MapPack pack) {
        return "mapgen_" + pack.id() + "_height";
    }

    /**
     * Writes the datapacks a pack needs into a datapacks folder: the ZIP's embedded datapack, and
     * one overriding the overworld height when the map needs a non-vanilla height.
     *
     * @return the folder names written (pack ids are {@code "file/" + name})
     */
    public static List<String> writeDatapacks(MapPack pack, Path datapacksDir) throws IOException {
        List<String> written = new ArrayList<>();
        if (pack.hasDatapack()) {
            Path root = datapacksDir.resolve(embeddedPackName(pack)).toAbsolutePath().normalize();
            for (Map.Entry<String, byte[]> file : pack.datapack().entrySet()) {
                Path target = root.resolve(file.getKey()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("unsafe datapack path " + file.getKey());
                }
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
            }
            written.add(embeddedPackName(pack));
        }
        if (!pack.layout().isVanilla()) {
            Path root = datapacksDir.resolve(dimensionPackName(pack));
            Files.createDirectories(root.resolve("data/minecraft/dimension_type"));
            Files.writeString(root.resolve("pack.mcmeta"), packMcmeta("World height for map " + pack.name()));
            Files.writeString(root.resolve("data/minecraft/dimension_type/overworld.json"), dimensionTypeJson(pack.layout()));
            written.add(dimensionPackName(pack));
        }
        return written;
    }

    static String packMcmeta(String description) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", description);
        pack.addProperty("min_format", SharedConstants.DATA_PACK_FORMAT_MAJOR);
        pack.addProperty("max_format", SharedConstants.DATA_PACK_FORMAT_MAJOR);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /** The vanilla overworld dimension type with the map's vertical extent. */
    static String dimensionTypeJson(DimensionLayout layout) throws IOException {
        JsonObject json;
        try (InputStream in = MapWorlds.class.getResourceAsStream(DIMENSION_TYPE_TEMPLATE)) {
            if (in == null) {
                throw new IOException("missing resource " + DIMENSION_TYPE_TEMPLATE);
            }
            json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        json.addProperty("min_y", layout.minY());
        json.addProperty("height", layout.height());
        json.addProperty("logical_height", layout.height());
        MapGen.LOG.debug("Overworld dimension type: min_y={} height={}", layout.minY(), layout.height());
        return new GsonBuilder().setPrettyPrinting().create().toJson(json);
    }
}
