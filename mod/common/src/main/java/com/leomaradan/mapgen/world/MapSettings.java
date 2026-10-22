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
 * here (world height, feature and structure filters); the terrain data stays in the ZIP.
 *
 * @param pack id of the map pack, empty when no ZIP was selected yet
 */
public record MapSettings(String pack, String name, int minY, int height, int seaLevel, boolean features, List<String> disabledFeatures,
                          List<String> disabledFeatureSteps, Optional<List<String>> onlyFeatures, boolean carvers,
                          boolean vanillaStructures, List<String> disabledStructures, Optional<List<String>> onlyStructures) {

    public static final Codec<MapSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("pack", "").forGetter(MapSettings::pack),
            Codec.STRING.optionalFieldOf("name", "").forGetter(MapSettings::name),
            Codec.INT.optionalFieldOf("min_y", DimensionLayout.VANILLA_MIN_Y).forGetter(MapSettings::minY),
            Codec.INT.optionalFieldOf("height", DimensionLayout.VANILLA_HEIGHT).forGetter(MapSettings::height),
            Codec.INT.optionalFieldOf("sea_level", 63).forGetter(MapSettings::seaLevel),
            Codec.BOOL.optionalFieldOf("features", true).forGetter(MapSettings::features),
            Codec.STRING.listOf().optionalFieldOf("disabled_features", List.of()).forGetter(MapSettings::disabledFeatures),
            Codec.STRING.listOf().optionalFieldOf("disabled_feature_steps", List.of()).forGetter(MapSettings::disabledFeatureSteps),
            Codec.STRING.listOf().optionalFieldOf("only_features").forGetter(MapSettings::onlyFeatures),
            Codec.BOOL.optionalFieldOf("carvers", true).forGetter(MapSettings::carvers),
            Codec.BOOL.optionalFieldOf("vanilla_structures", true).forGetter(MapSettings::vanillaStructures),
            Codec.STRING.listOf().optionalFieldOf("disabled_structures", List.of()).forGetter(MapSettings::disabledStructures),
            Codec.STRING.listOf().optionalFieldOf("only_structures").forGetter(MapSettings::onlyStructures)
    ).apply(i, i.stable(MapSettings::new)));

    /** Settings of a world type selected without a ZIP yet. */
    public static final MapSettings UNCONFIGURED = new MapSettings("", "", DimensionLayout.VANILLA_MIN_Y, DimensionLayout.VANILLA_HEIGHT, 63,
            true, List.of(), List.of(), Optional.empty(), true, true, List.of(), Optional.empty());

    public static MapSettings of(MapPack pack) {
        MapConfig config = pack.config();
        MapConfig.VanillaStructures structures = config.vanillaStructures();
        MapConfig.Features features = config.decoration().features();
        return new MapSettings(pack.id(), pack.name(), pack.layout().minY(), pack.layout().height(), config.seaLevel(),
                features.enabled(), features.disabled(), features.disabledSteps(), Optional.ofNullable(features.only()),
                config.decoration().carvers(), structures.enabled(), structures.disabled(), Optional.ofNullable(structures.only()));
    }

    public boolean isConfigured() {
        return !this.pack.isEmpty();
    }

    public MapConfig.Features featureFilter() {
        return new MapConfig.Features(this.features, this.disabledFeatures, this.disabledFeatureSteps, this.onlyFeatures.orElse(null));
    }

    public MapConfig.VanillaStructures structureFilter() {
        return new MapConfig.VanillaStructures(this.vanillaStructures, this.disabledStructures, this.onlyStructures.orElse(null));
    }
}
