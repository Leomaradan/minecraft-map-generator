package com.leomaradan.mapgen.world;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.leomaradan.mapgen.MapGen;
import com.leomaradan.mapgen.core.BiomeMapping;
import com.leomaradan.mapgen.core.DimensionLayout;
import com.leomaradan.mapgen.core.MapArchive;
import com.leomaradan.mapgen.core.MapConfig;
import com.leomaradan.mapgen.core.MapPack;
import com.leomaradan.mapgen.core.StructureEntry;
import com.leomaradan.mapgen.core.ValidationReport;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Turns a map ZIP into world settings: generators, dimensions, datapacks. */
public final class MapWorlds {
    private static final String OVERWORLD_TYPE_TEMPLATE = "/mapgen/overworld_dimension_type.json";
    private static final String NETHER_TYPE_TEMPLATE = "/mapgen/the_nether_dimension_type.json";
    private static final String END_TYPE_TEMPLATE = "/mapgen/the_end_dimension_type.json";
    private static final String LOGICAL_HEIGHT = "logical_height";
    private static final String HEIGHT = "height";
    private static final String DOES_NOT_EXIST = " does not exist";

    private MapWorlds() {
    }

    /**
     * Builds the generator of a map, checking that every biome it names exists.
     *
     * @param minY   bottom of the dimension it generates
     * @param height height of the dimension it generates
     * @return null if a biome is missing (the reasons are added to {@code report})
     */
    public static @Nullable MapChunkGenerator createGenerator(MapPack pack, HolderGetter<Biome> biomes, int minY, int height,
                                                              ValidationReport report) {
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
            report.error("outside biome " + pack.config().outside().biome() + DOES_NOT_EXIST);
        }
        if (report.hasErrors()) {
            return null;
        }
        return new MapChunkGenerator(new MapBiomeSource(pack.id(), pack.dimension().id(), mapped, outside),
                MapSettings.of(pack).withLayout(minY, height));
    }

    /**
     * Puts the generators of a ZIP's maps in the dimensions of a new world. The datapacks of
     * {@link #writeDatapacks} must be loaded (they define the dimension types of the maps).
     *
     * @param datapackDimensions dimensions defined by datapacks and mods
     * @return null when a map can't be used (the reasons are added to {@code report})
     */
    public static @Nullable WorldDimensions configureDimensions(MapArchive archive, WorldDimensions dimensions,
                                                                Registry<LevelStem> datapackDimensions, HolderLookup.Provider registries,
                                                                ValidationReport report) {
        Map<ResourceKey<LevelStem>, LevelStem> stems = new LinkedHashMap<>(dimensions.dimensions());
        if (archive.overworld() == null) {
            // The ZIP doesn't generate the overworld: it is a normal one.
            registries.lookupOrThrow(Registries.WORLD_PRESET).get(WorldPresets.NORMAL)
                    .flatMap(preset -> preset.value().overworld())
                    .ifPresent(stem -> stems.put(LevelStem.OVERWORLD, stem));
        }
        HolderGetter<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        HolderGetter<DimensionType> types = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
        for (MapPack pack : archive.dimensions()) {
            ValidationReport own = new ValidationReport();
            ResourceKey<LevelStem> key = ResourceKey.create(Registries.LEVEL_STEM, Identifier.parse(pack.dimension().id()));
            Holder<DimensionType> type = dimensionType(pack.dimension(), key, stems, datapackDimensions, types, own);
            if (type != null) {
                DimensionType value = type.value();
                if (!pack.dimension().sizedToMap() || pack.dimension().typeSetsHeight()) {
                    warnIfCut(pack, value, own);
                }
                MapChunkGenerator generator = createGenerator(pack, biomes, value.minY(), value.height(), own);
                if (generator != null) {
                    stems.put(key, new LevelStem(type, generator));
                }
            }
            report.addAll(own, archive.dimensions().size() > 1 ? pack.dimension().id() + ": " : "");
        }
        return report.hasErrors() ? null : new WorldDimensions(stems);
    }

    /** The dimension type of a map's dimension; null (with an error) when it doesn't exist. */
    private static @Nullable Holder<DimensionType> dimensionType(MapConfig.DimensionTarget target, ResourceKey<LevelStem> key,
                                                                Map<ResourceKey<LevelStem>, LevelStem> stems, Registry<LevelStem> datapackDimensions,
                                                                HolderGetter<DimensionType> types, ValidationReport report) {
        if (target.custom()) {
            Optional<LevelStem> existing = datapackDimensions.getOptional(key);
            if (existing.isPresent() && !(existing.get().generator() instanceof MapChunkGenerator)) {
                report.error("dimension " + target.id() + " already exists (added by a mod or a datapack); use \"dimension\": \""
                        + target.id() + "\" to replace its generator");
                return null;
            }
            String typeId = target.typeId() != null ? target.typeId() : target.id();
            Optional<? extends Holder<DimensionType>> type = types.get(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(typeId)));
            if (type.isEmpty()) {
                report.error("dimension type " + typeId + DOES_NOT_EXIST);
                return null;
            }
            return type.get();
        }
        LevelStem stem = stems.get(key);
        if (stem == null) {
            stem = datapackDimensions.getOptional(key).orElse(null);
        }
        if (stem == null) {
            report.error("dimension " + target.id() + DOES_NOT_EXIST + "; is the mod that adds it installed?");
            return null;
        }
        return stem.type();
    }

    /** A map put in a dimension of fixed height: warns when its terrain doesn't fit. */
    private static void warnIfCut(MapPack pack, DimensionType type, ValidationReport report) {
        int maxY = type.minY() + type.height() - 1;
        if (pack.lowestY() < type.minY() + 1 || pack.highestY() > maxY) {
            report.warn("the map goes from Y=" + pack.lowestY() + " to Y=" + pack.highestY() + " but " + pack.dimension().id() + " is from Y="
                    + type.minY() + " to Y=" + maxY + "; the terrain outside is cut");
        }
    }

    /** Extra checks needing registries: blocks and structures named by the maps. */
    public static void validateRegistries(MapArchive archive, HolderGetter<Structure> structures, ValidationReport report) {
        for (MapPack pack : archive.dimensions()) {
            ValidationReport own = new ValidationReport();
            for (String block : pack.blockIds()) {
                if (BuiltInRegistries.BLOCK.getOptional(Identifier.parse(block)).isEmpty()) {
                    own.error("block " + block + DOES_NOT_EXIST);
                }
            }
            for (StructureEntry entry : pack.structures()) {
                if (entry instanceof StructureEntry.Structure structure) {
                    reportMissingStructure(structure, id -> structures.get(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(id))).isPresent(), own);
                }
            }
            report.addAll(own, archive.dimensions().size() > 1 ? pack.dimension().id() + ": " : "");
        }
    }

    /** Warns about a structure that doesn't exist: it uses its fallback_id, or is skipped. */
    public static void reportMissingStructure(StructureEntry.Structure structure, Predicate<String> exists, ValidationReport report) {
        String resolved = structure.resolve(exists);
        String at = " (at " + structure.x() + ", " + structure.z() + ")";
        if (resolved == null) {
            report.warn("structure " + structure.id() + at + DOES_NOT_EXIST + (structure.fallbackId() != null ? ", nor its fallback_id "
                    + structure.fallbackId() : "") + ": it is skipped");
        } else if (!resolved.equals(structure.id())) {
            report.warn("structure " + structure.id() + at + DOES_NOT_EXIST + ": its fallback_id " + resolved + " is used");
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
    public static String embeddedPackName(MapArchive archive) {
        return "mapgen_" + archive.id() + "_datapack";
    }

    /** Name of the folder of the generated datapack holding the dimension types of the maps. */
    public static String dimensionPackName(MapArchive archive) {
        return "mapgen_" + archive.id() + "_dimensions";
    }

    /**
     * Writes the datapacks a ZIP needs into a datapacks folder: the ZIP's embedded datapack, and
     * one with the dimension types of its maps when they need one (a vanilla dimension resized to
     * the map, or a new dimension).
     *
     * @return the folder names written (pack ids are {@code "file/" + name})
     */
    public static List<String> writeDatapacks(MapArchive archive, Path datapacksDir) throws IOException {
        List<String> written = new ArrayList<>();
        if (archive.hasDatapack()) {
            Path root = datapacksDir.resolve(embeddedPackName(archive)).toAbsolutePath().normalize();
            for (Map.Entry<String, byte[]> file : archive.datapack().entrySet()) {
                Path target = root.resolve(file.getKey()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("unsafe datapack path " + file.getKey());
                }
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
            }
            written.add(embeddedPackName(archive));
        }
        Map<String, String> types = new LinkedHashMap<>();
        for (MapPack pack : archive.dimensions()) {
            String type = dimensionTypeJson(pack);
            if (type != null) {
                Identifier id = Identifier.parse(pack.dimension().id());
                types.put("data/" + id.getNamespace() + "/dimension_type/" + id.getPath() + ".json", type);
            }
        }
        if (!types.isEmpty()) {
            Path root = datapacksDir.resolve(dimensionPackName(archive));
            Files.createDirectories(root);
            Files.writeString(root.resolve("pack.mcmeta"), packMcmeta("Dimensions of map " + archive.name()));
            for (Map.Entry<String, String> type : types.entrySet()) {
                Path file = root.resolve(type.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, type.getValue());
            }
            written.add(dimensionPackName(archive));
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

    /**
     * The dimension type a map needs, or null when it uses an existing one as is:
     * <ul>
     * <li>a vanilla dimension resized to the map (with its vanilla look);</li>
     * <li>a new dimension: like the overworld, with the fields of an inline {@code parameters.type}, sized to the map unless the type sets its height.</li>
     * </ul>
     */
    static @Nullable String dimensionTypeJson(MapPack pack) throws IOException {
        MapConfig.DimensionTarget target = pack.dimension();
        DimensionLayout layout = pack.layout();
        JsonObject json;
        if (target.isVanilla()) {
            if (layout.equals(DimensionLayout.baseline(target))) {
                return null;
            }
            json = readTemplate(switch (target.id()) {
                case MapConfig.DimensionTarget.NETHER -> NETHER_TYPE_TEMPLATE;
                case MapConfig.DimensionTarget.END -> END_TYPE_TEMPLATE;
                default -> OVERWORLD_TYPE_TEMPLATE;
            });
            // The overworld's logical height follows the world (as before); the Nether keeps its lower one (portals, chorus fruits).
            int logical = target.isOverworld() ? layout.height() : Math.min(json.get(LOGICAL_HEIGHT).getAsInt(), layout.height());
            setHeight(json, layout, logical);
        } else if (target.custom() && target.sizedToMap()) {
            json = customTypeJson(target, layout);
        } else {
            return null;
        }
        MapGen.LOG.debug("Dimension type of {}: min_y={} height={}", target.id(), layout.minY(), layout.height());
        return new GsonBuilder().setPrettyPrinting().create().toJson(json);
    }

    /** The type of a new dimension: the overworld's sized to the map, with the fields of an inline {@code parameters.type}. */
    private static JsonObject customTypeJson(MapConfig.DimensionTarget target, DimensionLayout layout) throws IOException {
        JsonObject json = readTemplate(OVERWORLD_TYPE_TEMPLATE);
        setHeight(json, layout, layout.height());
        if (target.type() != null) {
            for (Map.Entry<String, JsonElement> field : target.type().getAsJsonObject().entrySet()) {
                json.add(field.getKey(), field.getValue().deepCopy());
            }
            // A type setting its own height keeps a logical height within it.
            if (json.get(LOGICAL_HEIGHT).getAsInt() > json.get(HEIGHT).getAsInt()) {
                json.add(LOGICAL_HEIGHT, json.get(HEIGHT).deepCopy());
            }
        }
        return json;
    }

    private static void setHeight(JsonObject json, DimensionLayout layout, int logicalHeight) {
        json.addProperty("min_y", layout.minY());
        json.addProperty(HEIGHT, layout.height());
        json.addProperty(LOGICAL_HEIGHT, logicalHeight);
    }

    private static JsonObject readTemplate(String resource) throws IOException {
        try (InputStream in = MapWorlds.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing resource " + resource);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
