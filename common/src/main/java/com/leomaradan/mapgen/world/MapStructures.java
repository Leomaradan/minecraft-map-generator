package com.leomaradan.mapgen.world;

import com.leomaradan.mapgen.MapGen;
import com.leomaradan.mapgen.core.MapPack;
import com.leomaradan.mapgen.core.StructureEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structures listed in {@code structures.json}: templates from the ZIP are pasted chunk by chunk
 * during decoration; registered structures are started in the chunk containing their position.
 */
final class MapStructures {
    private final MapPack pack;
    private final List<StructureEntry.Template> templates = new ArrayList<>();
    private final List<StructureEntry.Structure> forced = new ArrayList<>();
    /** Loaded templates by ZIP path; a template that failed to load is cached empty. */
    private final Map<String, StructureTemplate> loadedTemplates = new ConcurrentHashMap<>();
    private final Map<StructureEntry.Template, Placement> placements = new ConcurrentHashMap<>();

    private record Placement(StructureTemplate template, BlockPos origin, StructurePlaceSettings settings, BoundingBox box) {
    }

    MapStructures(MapPack pack) {
        this.pack = pack;
        for (StructureEntry entry : pack.structures()) {
            if (entry instanceof StructureEntry.Template template) {
                this.templates.add(template);
            } else if (entry instanceof StructureEntry.Structure structure) {
                this.forced.add(structure);
            }
        }
    }

    List<StructureEntry.Structure> forced() {
        return this.forced;
    }

    List<StructureEntry.Structure> forcedIn(ChunkPos chunk) {
        List<StructureEntry.Structure> result = new ArrayList<>(0);
        for (StructureEntry.Structure entry : this.forced) {
            if ((entry.x() >> 4) == chunk.x() && (entry.z() >> 4) == chunk.z()) {
                result.add(entry);
            }
        }
        return result;
    }

    void placeTemplates(WorldGenLevel level, ChunkAccess chunk, MapChunkGenerator generator) {
        if (this.templates.isEmpty()) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        BoundingBox chunkBox = new BoundingBox(pos.getMinBlockX(), level.getMinY(), pos.getMinBlockZ(), pos.getMaxBlockX(), level.getMaxY(),
                pos.getMaxBlockZ());
        for (StructureEntry.Template entry : this.templates) {
            Placement placement = this.placement(level, entry, generator);
            if (placement == null || !placement.box.intersects(chunkBox)) {
                continue;
            }
            StructurePlaceSettings settings = copy(placement.settings).setBoundingBox(chunkBox);
            settings.setRandom(MapChunkGenerator.templateRandom(level.getSeed(), entry));
            placement.template.placeInWorld(level, placement.origin, placement.origin, settings, settings.getRandom(placement.origin),
                    Block.UPDATE_CLIENTS);
        }
    }

    private @Nullable Placement placement(WorldGenLevel level, StructureEntry.Template entry, MapChunkGenerator generator) {
        Placement placement = this.placements.get(entry);
        if (placement != null) {
            return placement;
        }
        StructureTemplate template = this.template(level, entry.path());
        if (template == null) {
            return null;
        }
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation(entry.rotation()))
                .setMirror(mirror(entry.mirror()))
                .setIgnoreEntities(!entry.includeEntities())
                .addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK);
        int y = entry.y() != null ? entry.y() : generator.surfaceY(entry.x(), entry.z()) + entry.yOffset();
        BlockPos origin = new BlockPos(entry.x(), y, entry.z());
        placement = new Placement(template, origin, settings, template.getBoundingBox(settings, origin));
        this.placements.put(entry, placement);
        return placement;
    }

    private @Nullable StructureTemplate template(WorldGenLevel level, String path) {
        StructureTemplate cached = this.loadedTemplates.get(path);
        if (cached != null) {
            return cached;
        }
        byte[] data = this.pack.templates().get(path);
        if (data == null) {
            return null;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
            int version = NbtUtils.getDataVersion(tag, 500);
            StructureTemplate template = new StructureTemplate();
            template.load(level.holderLookup(Registries.BLOCK), DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), tag, version));
            this.loadedTemplates.put(path, template);
            return template;
        } catch (IOException | RuntimeException e) {
            MapGen.LOG.error("[{}] Can't read structure template {}", this.pack.name(), path, e);
            // Cache an empty template so the error is only logged once.
            StructureTemplate empty = new StructureTemplate();
            this.loadedTemplates.put(path, empty);
            return empty;
        }
    }

    private static StructurePlaceSettings copy(StructurePlaceSettings source) {
        StructurePlaceSettings copy = new StructurePlaceSettings()
                .setRotation(source.getRotation())
                .setMirror(source.getMirror())
                .setIgnoreEntities(source.isIgnoreEntities());
        source.getProcessors().forEach(copy::addProcessor);
        return copy;
    }

    private static Rotation rotation(StructureEntry.Rotation rotation) {
        return switch (rotation) {
            case NONE -> Rotation.NONE;
            case CLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            case CLOCKWISE_180 -> Rotation.CLOCKWISE_180;
            case COUNTERCLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    private static Mirror mirror(StructureEntry.Mirror mirror) {
        return switch (mirror) {
            case NONE -> Mirror.NONE;
            case LEFT_RIGHT -> Mirror.LEFT_RIGHT;
            case FRONT_BACK -> Mirror.FRONT_BACK;
        };
    }
}
