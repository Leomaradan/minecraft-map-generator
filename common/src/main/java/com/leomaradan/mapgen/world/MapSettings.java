package com.leomaradan.mapgen.world;

import com.leomaradan.mapgen.core.DimensionLayout;
import com.leomaradan.mapgen.core.MapConfig;
import com.leomaradan.mapgen.core.MapPack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Optional;

/**
 * Generator settings saved in level.dat. Everything needed before the map pack is loaded lives
 * here (world height, decoration flags, structure filter); the terrain data stays in the ZIP.
 *
 * @param pack id of the map pack, empty when no ZIP was selected yet
 */
public record MapSettings(String pack, String name, int minY, int height, int seaLevel, boolean features, boolean carvers,
                          boolean vanillaStructures, List<String> disabledStructures, Optional<List<String>> onlyStructures) {

    public static final Codec<MapSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("pack", "").forGetter(MapSettings::pack),
            Codec.STRING.optionalFieldOf("name", "").forGetter(MapSettings::name),
            Codec.INT.optionalFieldOf("min_y", DimensionLayout.VANILLA_MIN_Y).forGetter(MapSettings::minY),
            Codec.INT.optionalFieldOf("height", DimensionLayout.VANILLA_HEIGHT).forGetter(MapSettings::height),
            Codec.INT.optionalFieldOf("sea_level", 63).forGetter(MapSettings::seaLevel),
            Codec.BOOL.optionalFieldOf("features", true).forGetter(MapSettings::features),
            Codec.BOOL.optionalFieldOf("carvers", true).forGetter(MapSettings::carvers),
            Codec.BOOL.optionalFieldOf("vanilla_structures", true).forGetter(MapSettings::vanillaStructures),
            Codec.STRING.listOf().optionalFieldOf("disabled_structures", List.of()).forGetter(MapSettings::disabledStructures),
            Codec.STRING.listOf().optionalFieldOf("only_structures").forGetter(MapSettings::onlyStructures)
    ).apply(i, i.stable(MapSettings::new)));

    /** Settings of a world type selected without a ZIP yet. */
    public static final MapSettings UNCONFIGURED = new MapSettings("", "", DimensionLayout.VANILLA_MIN_Y, DimensionLayout.VANILLA_HEIGHT, 63,
            true, true, true, List.of(), Optional.empty());

    public static MapSettings of(MapPack pack) {
        MapConfig config = pack.config();
        MapConfig.VanillaStructures structures = config.vanillaStructures();
        return new MapSettings(pack.id(), pack.name(), pack.layout().minY(), pack.layout().height(), config.seaLevel(),
                config.decoration().features(), config.decoration().carvers(), structures.enabled(), structures.disabled(),
                Optional.ofNullable(structures.only()));
    }

    public boolean isConfigured() {
        return !this.pack.isEmpty();
    }

    public MapConfig.VanillaStructures structureFilter() {
        return new MapConfig.VanillaStructures(this.vanillaStructures, this.disabledStructures, this.onlyStructures.orElse(null));
    }
}
