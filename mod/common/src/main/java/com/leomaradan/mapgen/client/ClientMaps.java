package com.leomaradan.mapgen.client;

import com.leomaradan.mapgen.MapGen;
import com.leomaradan.mapgen.core.MapPack;
import com.leomaradan.mapgen.core.StructureEntry;
import com.leomaradan.mapgen.core.ValidationReport;
import com.leomaradan.mapgen.mixin.client.CreateWorldScreenInvoker;
import com.leomaradan.mapgen.pack.MapPacks;
import com.leomaradan.mapgen.world.MapChunkGenerator;
import com.leomaradan.mapgen.world.MapWorlds;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.PackRepository;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Client side of the world creation: applies the selected map pack to the Create World screen.
 * <p>
 * The ZIP's datapack (custom biomes...) and the generated height datapack are written into the
 * screen's temporary datapack folder, enabled, and the screen reloads its datapacks like after
 * using the Data Packs button. Once the registries contain the new biomes, the overworld
 * generator is replaced by a {@link MapChunkGenerator} for the pack.
 */
public final class ClientMaps {
    private static @Nullable MapPack selected;
    /** Pack waiting for the datapack reload to finish before its generator can be built. */
    private static @Nullable MapPack pendingAfterReload;
    /** Datapack folders written into the current screen's temporary datapack folder. */
    private static final List<String> installed = new ArrayList<>();
    private static @Nullable CreateWorldScreen currentScreen;

    private ClientMaps() {
    }

    /** Folder scanned for map ZIPs: {@code .minecraft/mapgen}. */
    public static Path mapsFolder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(MapPacks.WORLD_FOLDER).toAbsolutePath().normalize();
    }

    public static List<Path> listZips() {
        Path folder = mapsFolder();
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                    .toList();
        } catch (IOException e) {
            MapGen.LOG.warn("Can't list {}", folder, e);
            return List.of();
        }
    }

    public static @Nullable MapPack selected() {
        return selected;
    }

    /** A new Create World screen starts without any map applied. */
    public static void reset(CreateWorldScreen screen) {
        currentScreen = screen;
        selected = null;
        pendingAfterReload = null;
        installed.clear();
    }

    public static boolean isMapPreset(WorldCreationUiState state) {
        var preset = state.getWorldType().preset();
        return preset != null && preset.unwrapKey().map(MapGen.PRESET::equals).orElse(false);
    }

    /** True when the map world type is selected but no valid ZIP has been applied. */
    public static boolean needsSelection(WorldCreationUiState state) {
        return isMapPreset(state) && !(state.getSettings().selectedDimensions().overworld() instanceof MapChunkGenerator generator
                && generator.settings().isConfigured());
    }

    /**
     * Checks what can be checked before the datapacks are reloaded: ids must exist in the current
     * registries or be defined by the ZIP's own datapack.
     */
    public static void validateAgainst(MapPack pack, WorldCreationContext context, ValidationReport report) {
        var biomes = context.worldgenLoadContext().lookupOrThrow(Registries.BIOME);
        for (String id : pack.biomeIds()) {
            Identifier key = Identifier.parse(id);
            if (biomes.get(ResourceKey.create(Registries.BIOME, key)).isEmpty() && !definedByDatapack(pack, "worldgen/biome", key)) {
                report.error("biome " + id + " does not exist (not in the game, a mod, or the ZIP datapack)");
            }
        }
        var structures = context.worldgenLoadContext().lookupOrThrow(Registries.STRUCTURE);
        for (StructureEntry entry : pack.structures()) {
            if (entry instanceof StructureEntry.Structure structure) {
                MapWorlds.reportMissingStructure(structure, id -> structures.get(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(id))).isPresent()
                        || definedByDatapack(pack, "worldgen/structure", Identifier.parse(id)), report);
            }
        }
        for (String block : pack.blockIds()) {
            if (BuiltInRegistries.BLOCK.getOptional(Identifier.parse(block)).isEmpty()) {
                report.error("block " + block + " does not exist");
            }
        }
    }

    private static boolean definedByDatapack(MapPack pack, String folder, Identifier id) {
        return pack.datapack().containsKey("data/" + id.getNamespace() + "/" + folder + "/" + id.getPath() + ".json");
    }

    /** Applies a validated pack to the Create World screen. */
    public static void apply(CreateWorldScreen screen, MapPack pack) {
        MapPacks.register(pack);
        selected = pack;
        WorldCreationUiState state = screen.getUiState();
        CreateWorldScreenInvoker invoker = (CreateWorldScreenInvoker) screen;
        Pair<Path, PackRepository> packs = invoker.mapgen$getDataPackSelectionSettings(state.getSettings().dataConfiguration());
        if (packs == null) {
            return;
        }
        Path dir = packs.getFirst();
        PackRepository repository = packs.getSecond();
        List<String> enabled = new ArrayList<>(repository.getSelectedIds());
        if (!installed.isEmpty()) {
            // The reload re-reads the current generator: drop the previous map first, its biomes may go away.
            resetToPreset(state);
        }
        try {
            removeInstalled(dir, enabled);
            installed.addAll(MapWorlds.writeDatapacks(pack, dir));
        } catch (IOException e) {
            MapGen.LOG.error("Can't install the datapacks of {}", pack.name(), e);
            fail(Component.translatable("mapgen.error.datapack", e.getMessage()));
            return;
        }
        for (String name : installed) {
            enabled.add("file/" + name);
        }
        if (enabled.equals(state.getSettings().dataConfiguration().dataPacks().getEnabled())) {
            // Same datapacks as before: no reload needed.
            buildGenerator(state, pack);
            return;
        }
        repository.reload();
        repository.setSelected(enabled);
        pendingAfterReload = pack;
        invoker.mapgen$tryApplyNewDataPacks(repository, false, config -> {
            pendingAfterReload = null;
            ClientCompat.setScreen(Minecraft.getInstance(), screen);
        });
    }

    /** Called whenever the screen's settings are replaced (in particular after a datapack reload). */
    public static void onSettingsReplaced(WorldCreationUiState state) {
        MapPack pack = pendingAfterReload;
        if (pack == null) {
            return;
        }
        pendingAfterReload = null;
        buildGenerator(state, pack);
    }

    private static void buildGenerator(WorldCreationUiState state, MapPack pack) {
        ValidationReport report = new ValidationReport();
        MapChunkGenerator generator = MapWorlds.createGenerator(pack, state.getSettings().worldgenLoadContext().lookupOrThrow(Registries.BIOME), report);
        if (generator == null) {
            selected = null;
            MapGen.LOG.error("Map {} can't be used:\n{}", pack.name(), report);
            fail(Component.literal(report.errors().getFirst()));
            return;
        }
        state.updateDimensions((registries, dimensions) -> dimensions.replaceOverworldGenerator(registries, generator));
        MapGen.LOG.info("Selected map {} ({})", pack.name(), pack.summary());
    }

    /**
     * Listener on the Create World screen state: when the user switches away from the map world
     * type, remove the map's datapacks so they don't end up in another kind of world.
     */
    public static void onUiChanged(WorldCreationUiState state) {
        CreateWorldScreen screen = currentScreen;
        if (screen == null || installed.isEmpty() || isMapPreset(state) || pendingAfterReload != null || state.getWorldType().preset() == null) {
            return;
        }
        CreateWorldScreenInvoker invoker = (CreateWorldScreenInvoker) screen;
        Pair<Path, PackRepository> packs = invoker.mapgen$getDataPackSelectionSettings(state.getSettings().dataConfiguration());
        selected = null;
        if (packs == null) {
            return;
        }
        List<String> enabled = new ArrayList<>(packs.getSecond().getSelectedIds());
        try {
            removeInstalled(packs.getFirst(), enabled);
        } catch (IOException e) {
            MapGen.LOG.warn("Can't remove the map datapacks", e);
        }
        packs.getSecond().reload();
        packs.getSecond().setSelected(enabled);
        invoker.mapgen$tryApplyNewDataPacks(packs.getSecond(), false, config -> ClientCompat.setScreen(Minecraft.getInstance(), screen));
    }

    private static void resetToPreset(WorldCreationUiState state) {
        var preset = state.getWorldType().preset();
        if (preset != null) {
            state.updateDimensions((registries, dimensions) -> preset.value().createWorldDimensions());
        }
    }

    private static void removeInstalled(Path dir, List<String> enabled) throws IOException {
        for (String name : installed) {
            enabled.remove("file/" + name);
            Path folder = dir.resolve(name);
            if (Files.exists(folder)) {
                try (Stream<Path> files = Files.walk(folder)) {
                    for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
        }
        installed.clear();
    }

    private static void fail(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        SystemToast.add(ClientCompat.toastManager(minecraft), SystemToast.SystemToastId.PACK_LOAD_FAILURE, Component.translatable("mapgen.error.title"), message);
    }
}
