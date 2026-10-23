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
 * @param pack          id of the map pack, empty when no ZIP was selected yet
 * @param biomeFeatures feature filters of biomes.json entries, by biome (the generator lists each biome's features before the pack is loaded)
 * @param carvers       cave carvers (and canyon carvers when {@code canyons} is absent, as before it existed)
 * @param noiseCaves    noise caves; when absent (worlds created before it existed) they follow {@code carvers}
 */
public record MapSettings(String pack, String name, int minY, int height, int seaLevel, boolean features, List<String> disabledFeatures,
                          List<String> disabledFeatureSteps, Optional<List<String>> onlyFeatures, List<BiomeFeatures> biomeFeatures,
                          boolean carvers, Optional<Boolean> canyons, Optional<Boolean> noiseCaves, boolean vanillaStructures, List<String> disabledStructures,
                          Optional<List<String>> onlyStructures) {

    /** Feature filter of one biomes.json entry or substitute. */
    public record BiomeFeatures(String biome, boolean enabled, List<String> disabled, List<String> disabledSteps, Optional<List<String>> only) {
        public static final Codec<BiomeFeatures> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("biome").forGetter(BiomeFeatures::biome),
                Codec.BOOL.optionalFieldOf("enabled", true).forGetter(BiomeFeatures::enabled),
                Codec.STRING.listOf().optionalFieldOf("disabled", List.of()).forGetter(BiomeFeatures::disabled),
                Codec.STRING.listOf().optionalFieldOf("disabled_steps", List.of()).forGetter(BiomeFeatures::disabledSteps),
                Codec.STRING.listOf().optionalFieldOf("only").forGetter(BiomeFeatures::only)
        ).apply(i, i.stable(BiomeFeatures::new)));

        static BiomeFeatures of(String biome, MapConfig.Features filter) {
            return new BiomeFeatures(biome, filter.enabled(), filter.disabled(), filter.disabledSteps(), Optional.ofNullable(filter.only()));
        }

        public MapConfig.Features filter() {
            return new MapConfig.Features(this.enabled, this.disabled, this.disabledSteps, this.only.orElse(null));
        }
    }

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
            BiomeFeatures.CODEC.listOf().optionalFieldOf("biome_features", List.of()).forGetter(MapSettings::biomeFeatures),
            Codec.BOOL.optionalFieldOf("carvers", true).forGetter(MapSettings::carvers),
            Codec.BOOL.optionalFieldOf("canyons").forGetter(MapSettings::canyons),
            Codec.BOOL.optionalFieldOf("noise_caves").forGetter(MapSettings::noiseCaves),
            Codec.BOOL.optionalFieldOf("vanilla_structures", true).forGetter(MapSettings::vanillaStructures),
            Codec.STRING.listOf().optionalFieldOf("disabled_structures", List.of()).forGetter(MapSettings::disabledStructures),
            Codec.STRING.listOf().optionalFieldOf("only_structures").forGetter(MapSettings::onlyStructures)
    ).apply(i, i.stable(MapSettings::new)));

    /** Settings of a world type selected without a ZIP yet. */
    public static final MapSettings UNCONFIGURED = new MapSettings("", "", DimensionLayout.VANILLA_MIN_Y, DimensionLayout.VANILLA_HEIGHT, 63,
            true, List.of(), List.of(), Optional.empty(), List.of(), true, Optional.empty(), Optional.empty(), true, List.of(),
            Optional.empty());

    public static MapSettings of(MapPack pack) {
        MapConfig config = pack.config();
        MapConfig.VanillaStructures structures = config.vanillaStructures();
        MapConfig.Features features = config.decoration().features();
        MapConfig.Carvers carvers = config.decoration().carvers();
        List<BiomeFeatures> biomeFeatures = pack.biomeMapping().slots().stream()
                .filter(slot -> slot.features() != null)
                .map(slot -> BiomeFeatures.of(slot.biome(), slot.features()))
                .toList();
        return new MapSettings(pack.id(), pack.name(), pack.layout().minY(), pack.layout().height(), config.seaLevel(),
                features.enabled(), features.disabled(), features.disabledSteps(), Optional.ofNullable(features.only()), biomeFeatures,
                carvers.cave(), Optional.of(carvers.canyon()), Optional.of(carvers.noise()), structures.enabled(), structures.disabled(), Optional.ofNullable(structures.only()));
    }

    public boolean isConfigured() {
        return !this.pack.isEmpty();
    }

    public MapConfig.Features featureFilter() {
        return new MapConfig.Features(this.features, this.disabledFeatures, this.disabledFeatureSteps, this.onlyFeatures.orElse(null));
    }

    public MapConfig.Carvers carverFilter() {
        return new MapConfig.Carvers(this.carvers, this.canyons.orElse(this.carvers), this.noiseCaves.orElse(this.carvers));
    }

    public MapConfig.VanillaStructures structureFilter() {
        return new MapConfig.VanillaStructures(this.vanillaStructures, this.disabledStructures, this.onlyStructures.orElse(null));
    }
}
