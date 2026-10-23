#!/usr/bin/env python3
"""Builds the example map packs (example.zip, example-tall.zip, example-dimensions.zip) from scratch.

Only uses the Python standard library: PNG and NBT files are written by hand.

    python3 example/make_example.py            # writes the three ZIPs into example/

example.zip       256x256, 8-bit maps: an island with a mountain lake, a lava pool, a custom biome
                  (from the embedded datapack), a structure template and forced structures.
example-tall.zip  same island with 16-bit maps scaled to a 700-block-high mountain; the world height
                  is raised automatically through a generated dimension type.
example-dimensions.zip  the island in the overworld, a volcanic island in the Nether, a floating island
                  (mapgen:sky, with a bottom map) and a cave world (mapgen:caves, with a ceiling map),
                  in the "dimensions" format of map.json.
"""
import gzip
import io
import json
import math
import os
import struct
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
SIZE = 256
DATA_VERSION = 4786  # Minecraft 26.1 (upgraded by 26.2)
# The datapack works on Minecraft 26.1 (data pack format 101), 26.2 (107) and 26.3 (121). Its
# files are in the 26.1/26.2 format; the mc26_3 overlay replaces them from format 121, where biome
# mob spawns became the minecraft:gameplay/natural_mob_spawns attribute.
PACK_MCMETA = {
    "pack": {"description": "Example Island biomes", "min_format": 101, "max_format": 121},
    "overlays": {"entries": [{"min_format": 121, "max_format": 121, "directory": "mc26_3"}]},
}
SCHEMAS = "https://raw.githubusercontent.com/Leomaradan/minecraft-map-generator/main/docs/schemas/"

# ---------------------------------------------------------------------------------------------
# PNG / NBT writers
# ---------------------------------------------------------------------------------------------


def png(width, height, rows, bit_depth=8, color_type=0):
    """rows: list of bytes (raw samples, no filter byte)."""
    def chunk(kind, data):
        c = struct.pack(">I", len(data)) + kind + data
        return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + r for r in rows)
    ihdr = struct.pack(">IIBBBBB", width, height, bit_depth, color_type, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def gray8(values):
    return png(len(values[0]), len(values), [bytes(max(0, min(255, int(v))) for v in row) for row in values], 8, 0)


def gray16(values):
    return png(SIZE, SIZE, [b"".join(struct.pack(">H", max(0, min(65535, int(v)))) for v in row) for row in values], 16, 0)


def rgb(values):
    return png(SIZE, SIZE, [b"".join(struct.pack(">BBB", (c >> 16) & 255, (c >> 8) & 255, c & 255) for c in row) for row in values], 8, 2)


TAG_BYTE, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 1, 3, 8, 9, 10


def nbt_payload(tag, value):
    if tag == TAG_INT:
        return struct.pack(">i", value)
    if tag == TAG_STRING:
        data = value.encode("utf-8")
        return struct.pack(">H", len(data)) + data
    if tag == TAG_LIST:
        item_tag, items = value
        return struct.pack(">bi", item_tag if items else 0, len(items)) + b"".join(nbt_payload(item_tag, i) for i in items)
    if tag == TAG_COMPOUND:
        out = b""
        for name, (t, v) in value.items():
            out += struct.pack(">b", t) + nbt_payload(TAG_STRING, name) + nbt_payload(t, v)
        return out + b"\x00"
    raise ValueError(tag)


def nbt_file(root):
    data = struct.pack(">b", TAG_COMPOUND) + nbt_payload(TAG_STRING, "") + nbt_payload(TAG_COMPOUND, root)
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0) as f:
        f.write(data)
    return buf.getvalue()


def hut_template():
    """A 7x5x7 cobblestone hut with a plank floor, glass windows and a door opening."""
    palette = ["minecraft:cobblestone", "minecraft:oak_planks", "minecraft:glass", "minecraft:air", "minecraft:torch"]
    blocks = []
    w, h = 7, 5
    for x in range(w):
        for y in range(h):
            for z in range(w):
                edge = x in (0, w - 1) or z in (0, w - 1)
                if y == 0:
                    state = 1
                elif y == h - 1:
                    state = 1
                elif edge:
                    window = y == 2 and (x == w // 2 or z == w // 2)
                    door = z == 0 and x == w // 2 and y in (1, 2)
                    state = 3 if door else 2 if window else 0
                elif (x, y, z) == (1, 1, 1):
                    state = 4
                else:
                    state = 3
                blocks.append({"pos": (TAG_LIST, (TAG_INT, [x, y, z])), "state": (TAG_INT, state)})
    root = {
        "DataVersion": (TAG_INT, DATA_VERSION),
        "size": (TAG_LIST, (TAG_INT, [w, h, w])),
        "palette": (TAG_LIST, (TAG_COMPOUND, [{"id": (TAG_STRING, name)} for name in palette])),
        "blocks": (TAG_LIST, (TAG_COMPOUND, blocks)),
        "entities": (TAG_LIST, (TAG_COMPOUND, [])),
    }
    return nbt_file(root)


# ---------------------------------------------------------------------------------------------
# Terrain
# ---------------------------------------------------------------------------------------------

OCEAN, BEACH, PLAINS, FOREST, DESERT, CRYSTAL, PEAKS = 0x1E3C8C, 0xF0DC82, 0x5DBB4F, 0x22662A, 0xE8C060, 0xC850E6, 0xF0F0F0

LAKE = (150, 110, 16)       # center x, z, radius (pixels)
LAVA = (95, 160, 5)
CRYSTAL_ZONE = (175, 165, 30)
DESERT_ZONE = (80, 90, 28)


def dist(x, z, cx, cz):
    return math.hypot(x - cx, z - cz)


def terrain():
    """Returns heights (blocks), water surface (0 = none), lava surface (0 = none), biome colors."""
    heights, water, lava, biomes = [], [], [], []
    c = SIZE / 2
    for z in range(SIZE):
        hr, wr, lr, br = [], [], [], []
        for x in range(SIZE):
            d = dist(x, z, c, c) / (SIZE / 2)  # 0 at the center, 1 at the edge
            wobble = 0.06 * math.sin(x * 0.11) + 0.05 * math.cos(z * 0.09)
            island = max(0.0, 1.0 - (d + wobble) * 1.25)
            mountain = max(0.0, 1.0 - dist(x, z, 128, 128) / 38.0)
            h = 40 + island * 40 + mountain ** 1.6 * 70
            w = 62 if h < 62 else 0
            ld = dist(x, z, *LAKE[:2])
            if ld < LAKE[2] + 4:
                # Mountain lake: a plateau with a rim, filled to Y=92.
                h = max(h, 96) if ld >= LAKE[2] else 84 + (ld / LAKE[2]) * 6
                w = 92 if ld < LAKE[2] else 0
            lv = 0
            pd = dist(x, z, *LAVA[:2])
            if pd < LAVA[2] + 2:
                h = max(h, 75) if pd >= LAVA[2] else 70
                lv = 74 if pd < LAVA[2] else 0
            if h < 62:
                b = OCEAN
            elif h < 65:
                b = BEACH
            elif mountain > 0.55:
                b = PEAKS
            elif dist(x, z, *CRYSTAL_ZONE[:2]) < CRYSTAL_ZONE[2]:
                b = CRYSTAL
            elif dist(x, z, *DESERT_ZONE[:2]) < DESERT_ZONE[2]:
                b = DESERT
            elif (x // 24 + z // 24) % 3 == 0:
                b = FOREST
            else:
                b = PLAINS
            hr.append(h)
            wr.append(w)
            lr.append(lv)
            br.append(b)
        heights.append(hr)
        water.append(wr)
        lava.append(lr)
        biomes.append(br)
    return heights, water, lava, biomes


BIOME_MAPPING = {
    "$schema": SCHEMAS + "biomes.schema.json",
    "#1E3C8C": "minecraft:ocean",
    "#F0DC82": "minecraft:beach",
    "#5DBB4F": {"biome": "minecraft:plains", "biome_river": "minecraft:river"},
    "#22662A": {"biome": "minecraft:forest", "biome_underground": "minecraft:lush_caves"},
    "#E8C060": {"biome": "minecraft:desert", "biome_border": "minecraft:savanna", "border_width": 3},
    "#C850E6": {"biome": "example:crystal_fields", "top": "minecraft:moss_block", "filler": "minecraft:dirt", "filler_depth": 4},
    "#F0F0F0": {"biome": "minecraft:snowy_slopes", "top": "minecraft:snow_block", "filler": "minecraft:stone",
                "biome_river": {"biome": "minecraft:frozen_river", "underwater": "minecraft:gravel"},
                "biome_underground": "minecraft:dripstone_caves"},
}


def structures():
    # Positions are heightmap pixels (the map is centered on 0, 0: pixel 128, 128 is X=0, Z=0).
    return {"$schema": SCHEMAS + "structures.schema.json", "structures": [
        {"type": "template", "path": "structures/hut.nbt", "x": 100, "y": "surface", "z": 150, "rotation": "clockwise_90"},
        {"type": "template", "path": "structures/hut.nbt", "x": 165, "y": "surface", "z": 160},
        {"type": "structure", "id": "minecraft:village_plains", "x": 60, "z": 140},
        {"type": "structure", "id": "minecraft:pillager_outpost", "fallback_id": "minecraft:village_plains", "x": 180, "z": 195},
    ]}


def build(path, tall):
    heights, water, lava, biomes = terrain()
    config = {
        "$schema": SCHEMAS + "map.schema.json",
        "format": 1,
        "name": "Example Island" + (" (tall)" if tall else ""),
        "outside": {"mode": "flat", "ground_level": 40, "water_level": 62, "biome": "minecraft:deep_ocean"},
        "sea_level": 62,
        "spawn": {"x": 0, "z": 30},
        "vanilla_structures": {"disabled": ["minecraft:mineshafts", "minecraft:ocean_ruins"]},
        # The map has its own lava pool: no random lava lakes.
        "decoration": {"features": {"disabled": ["minecraft:lake_lava_surface", "minecraft:lake_lava_underground"]}},
    }
    if tall:
        # 16-bit maps: values 0..65535 map linearly onto Y 0..700; the mountain is 4.5x taller.
        def to16(h):
            return 0 if h == 0 else (h if h < 80 else 80 + (h - 80) * 4.5) / 700.0 * 65535
        heights = [[to16(h) for h in row] for row in heights]
        water = [[to16(v) for v in row] for row in water]
        lava = [[to16(v) for v in row] for row in lava]
        config["height"] = {"min_y": 0, "max_y": 700}
        encode = gray16
    else:
        encode = gray8

    write_zip(path, config, encode(heights), encode(water), encode(lava), rgb(biomes))


def write_zip(path, config, heightmap, water, lava, biomes, extra=None):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("map.json", json.dumps(config, indent=2))
        z.writestr("heightmap.png", heightmap)
        z.writestr("water.png", water)
        z.writestr("lava.png", lava)
        z.writestr("biomes.png", biomes)
        z.writestr("biomes.json", json.dumps(BIOME_MAPPING, indent=2))
        z.writestr("structures.json", json.dumps(structures(), indent=2))
        z.writestr("structures/hut.nbt", hut_template())
        z.writestr("datapack/pack.mcmeta", json.dumps(PACK_MCMETA, indent=2))
        src = os.path.join(HERE, "src", "datapack")
        for root, _, files in os.walk(src):
            for name in files:
                full = os.path.join(root, name)
                z.write(full, "datapack/" + os.path.relpath(full, src).replace(os.sep, "/"))
        for name, data in (extra or {}).items():
            z.writestr(name, data)
    print("wrote", path)


def volcano(size=96):
    """A volcanic island in a lava sea: heights and lava surface (0 = none)."""
    heights, lava = [], []
    c = size / 2
    for z in range(size):
        hr, lr = [], []
        for x in range(size):
            d = dist(x, z, c, c) / c
            wobble = 0.08 * math.sin(x * 0.3) + 0.08 * math.cos(z * 0.25)
            h = 20 + max(0.0, 1.0 - d - wobble) * 90
            if d < 0.12:
                h = 80  # crater, filled with lava
            hr.append(h)
            lr.append(86 if d < 0.12 else (32 if h < 32 else 0))
        heights.append(hr)
        lava.append(lr)
    return heights, lava


def sky_island(size=64):
    """A round floating island: its top, and its underside (a cone below it). 0 around it is empty
    (the sky map has a void outside and no bedrock)."""
    heights, bottoms = [], []
    c = size / 2
    for z in range(size):
        row, brow = [], []
        for x in range(size):
            d = dist(x, z, c, c) / c
            row.append(0 if d > 0.9 else 140 + (0.9 - d) * 30)
            brow.append(0 if d > 0.9 else 138 - (0.9 - d) * 60)
        heights.append(row)
        bottoms.append(brow)
    return heights, bottoms


def cave_world(size=96):
    """A cave world: a floor with hills and lava pools, under a rolling ceiling."""
    floors, ceilings, lava = [], [], []
    for z in range(size):
        frow, crow, lrow = [], [], []
        for x in range(size):
            floor = 40 + 8 * math.sin(x * 0.15) * math.cos(z * 0.12) + 6 * math.sin((x + z) * 0.05)
            ceiling = 90 + 10 * math.cos(x * 0.09) * math.sin(z * 0.11)
            frow.append(floor)
            crow.append(ceiling)
            lrow.append(38 if floor < 36 else 0)
        floors.append(frow)
        ceilings.append(crow)
        lava.append(lrow)
    return floors, ceilings, lava


def build_dimensions(path):
    heights, water, lava, biomes = terrain()
    nether_heights, nether_lava = volcano()
    island = {
        "name": "Example Islands",
        "dimension": "minecraft:overworld",
        "outside": {"mode": "flat", "ground_level": 40, "water_level": 62, "biome": "minecraft:deep_ocean"},
        "sea_level": 62,
        "spawn": {"x": 0, "z": 30},
        "decoration": {"features": {"disabled": ["minecraft:lake_lava_surface", "minecraft:lake_lava_underground"]}},
    }
    nether = {
        "dimension": "minecraft:the_nether",
        # Files not named here would be the overworld's (heightmap.png, biomes.png...): null means none.
        "files": {"heightmap": "nether/heightmap.png", "lava": "nether/lava.png", "water": None, "biomes": None,
                  "structures": None, "datapack": None},
        "outside": {"mode": "flat", "ground_level": 20, "water_level": 0, "biome": "minecraft:basalt_deltas"},
        "sea_level": 32,
        "blocks": {"default": "minecraft:netherrack", "bedrock": True},
        "decoration": {"carvers": False},
    }
    sky = {
        "dimension": {"name": "Sky", "parameters": {"type": {"ambient_light": 0.2}}},
        "files": {"heightmap": "sky/heightmap.png", "bottom": "sky/bottom.png", "water": None, "lava": None, "biomes": None,
                  "structures": None, "datapack": None},
        "outside": {"mode": "void", "biome": "minecraft:cherry_grove"},
        # Void around the map and no bedrock: heightmap pixels at 0 are empty.
        "blocks": {"bedrock": False},
        "decoration": {"carvers": False},
    }
    caves = {
        "dimension": {"name": "caves", "parameters": {"type": {"has_skylight": False, "has_ceiling": True, "ambient_light": 0.1}}},
        "files": {"heightmap": "caves/floor.png", "ceiling": "caves/ceiling.png", "lava": "caves/lava.png", "water": None,
                  "biomes": None, "structures": None, "datapack": None},
        "outside": {"mode": "void", "biome": "minecraft:dripstone_caves"},
        "blocks": {"default": ["minecraft:stone", "minecraft:deepslate"], "ceiling": "minecraft:deepslate"},
    }
    config = {"$schema": SCHEMAS + "map.schema.json", "dimensions": [island, nether, sky, caves]}
    sky_heights, sky_bottoms = sky_island()
    cave_floors, cave_ceilings, cave_lava = cave_world()
    extra = {
        "nether/heightmap.png": gray8(nether_heights),
        "nether/lava.png": gray8(nether_lava),
        "sky/heightmap.png": gray8(sky_heights),
        "sky/bottom.png": gray8(sky_bottoms),
        "caves/floor.png": gray8(cave_floors),
        "caves/ceiling.png": gray8(cave_ceilings),
        "caves/lava.png": gray8(cave_lava),
    }
    write_zip(path, config, gray8(heights), gray8(water), gray8(lava), rgb(biomes), extra)


if __name__ == "__main__":
    build(os.path.join(HERE, "example.zip"), tall=False)
    build(os.path.join(HERE, "example-tall.zip"), tall=True)
    build_dimensions(os.path.join(HERE, "example-dimensions.zip"))
