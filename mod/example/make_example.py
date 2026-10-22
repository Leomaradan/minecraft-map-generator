#!/usr/bin/env python3
"""Builds the example map packs (example.zip, example-tall.zip) from scratch.

Only uses the Python standard library: PNG and NBT files are written by hand.

    python3 example/make_example.py            # writes example/example.zip and example/example-tall.zip

example.zip       256x256, 8-bit maps: an island with a mountain lake, a lava pool, a custom biome
                  (from the embedded datapack), a structure template and forced structures.
example-tall.zip  same island with 16-bit maps scaled to a 700-block-high mountain; the world height
                  is raised automatically through a generated dimension type.
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
DATA_VERSION = 5023  # Minecraft 26.3
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
    return png(SIZE, SIZE, [bytes(max(0, min(255, int(v))) for v in row) for row in values], 8, 0)


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
    # Pixel (px, pz) -> world (px - 128, pz - 128): the map is centered on 0, 0.
    return {"$schema": SCHEMAS + "structures.schema.json", "structures": [
        {"type": "template", "path": "structures/hut.nbt", "x": 100 - 128, "y": "surface", "z": 150 - 128, "rotation": "clockwise_90"},
        {"type": "template", "path": "structures/hut.nbt", "x": 165 - 128, "y": "surface", "z": 160 - 128},
        {"type": "structure", "id": "minecraft:village_plains", "x": 60 - 128, "z": 140 - 128},
        {"type": "structure", "id": "minecraft:pillager_outpost", "x": 180 - 128, "z": 195 - 128},
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

    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("map.json", json.dumps(config, indent=2))
        z.writestr("heightmap.png", encode(heights))
        z.writestr("water.png", encode(water))
        z.writestr("lava.png", encode(lava))
        z.writestr("biomes.png", rgb(biomes))
        z.writestr("biomes.json", json.dumps(BIOME_MAPPING, indent=2))
        z.writestr("structures.json", json.dumps(structures(), indent=2))
        z.writestr("structures/hut.nbt", hut_template())
        z.writestr("datapack/pack.mcmeta", json.dumps({"pack": {"description": "Example Island biomes", "min_format": 121, "max_format": 121}}, indent=2))
        src = os.path.join(HERE, "src", "datapack")
        for root, _, files in os.walk(src):
            for name in files:
                full = os.path.join(root, name)
                z.write(full, "datapack/" + os.path.relpath(full, src).replace(os.sep, "/"))
    print("wrote", path)


if __name__ == "__main__":
    build(os.path.join(HERE, "example.zip"), tall=False)
    build(os.path.join(HERE, "example-tall.zip"), tall=True)
