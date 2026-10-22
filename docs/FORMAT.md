# Map ZIP format

A map is a ZIP file. Only `map.json` and the heightmap are required. File names below are the
defaults; they can be changed in `map.json` (`files`). If everything is inside a single top-level
folder in the ZIP, that folder is ignored.

```
map.json            REQUIRED  world configuration
heightmap.png       REQUIRED  ground height, 8-bit or 16-bit grayscale
biomes.png          optional  biome map (colors), same size as the heightmap
biomes.json         optional  color -> biome mapping (required with biomes.png)
water.png           optional  water surface height, same size and bit depth as the heightmap
lava.png            optional  lava surface height, same size and bit depth as the heightmap
structures.json     optional  structures to place at given coordinates
structures/*.nbt    optional  structure templates referenced by structures.json
datapack/           optional  a regular datapack (pack.mcmeta, data/...) installed in the world
```

`mod/example/make_example.py` builds two complete examples (`mod/example/example.zip`,
`mod/example/example-tall.zip`).

## Coordinates

Pixel `(0, 0)` is the north-west corner (smallest X and Z). By default the image is centered on
X=0, Z=0. A pixel covers `horizontal_scale` blocks (1 by default). Pixel `(px, pz)` is at world
`X = origin_x + px * horizontal_scale`, `Z = origin_z + pz * horizontal_scale`.

## Heightmap

Grayscale PNG, **8-bit (256 levels) or 16-bit (65,536 levels)**. Color images are accepted (the
red channel is used) with a warning. A pixel value `v` gives the Y of the top ground block:

```
y = base_y + v * scale                      ("height": {"base_y": ..., "scale": ...})
y = min_y + v / max_value * (max_y - min_y)  ("height": {"min_y": ..., "max_y": ...})
```

`max_value` is 255 (8-bit) or 65535 (16-bit). Without a `height` section:

* 8-bit: `y = v` (value 64 is Y=64);
* 16-bit: the full range maps onto Y 0..255, so 16-bit maps look like 8-bit ones with sub-block
  precision.

When `horizontal_scale` is above 1, heights are interpolated (bilinear) between pixels.

## World height

The world is tall enough for the map: by default its bottom is at least 16 blocks below the
lowest ground (never above vanilla's -64), and its top at least 64 blocks above the highest
ground or fluid (never below vanilla's 320). When that differs from vanilla, the mod installs a
small datapack in the world that changes the overworld height. Minecraft's limits: Y from -2032
to 2031, at most 4064 blocks tall; terrain outside the limits is cut (with a warning).

You can also set the height yourself: `"dimension": {"min_y": -64, "height": 1024}` (or
`"max_y"` instead of `"height"`); both must be multiples of 16.

## Water and lava maps

Same size **and same bit depth** as the heightmap (a 16-bit water map with an 8-bit heightmap is
rejected), and the same value-to-Y mapping. A pixel gives the Y of the **fluid surface**; `0`
means no fluid. Fluid fills the column from just above the ground up to that surface. If the
surface is not above the ground, nothing is placed. Lava wins over water when both are higher
than the ground.

Example: a disc of value 92 on ground at 84 makes a lake 8 blocks deep with its surface at Y=92.

Fluids are placed still (they don't flow until a neighbor block changes), so the edges of a lake
should be ground at least as high as the surface.

## Biome map and `biomes.json`

`biomes.png` is an RGB image of the same size as the heightmap. `biomes.json` maps each color to
a biome:

```json
{
  "#1E3C8C": "minecraft:ocean",
  "#5DBB4F": "plains",
  "240,220,130": "minecraft:beach",
  "#C850E6": {
    "biome": "example:crystal_fields",
    "top": "minecraft:moss_block",
    "filler": "minecraft:dirt",
    "filler_depth": 4,
    "underwater": "minecraft:clay"
  }
}
```

* Colors: `#RRGGBB`, `RRGGBB`, `0xRRGGBB` or `r,g,b`. Ids without namespace use `minecraft:`.
* Biomes can come from Minecraft, from other mods, or from the ZIP's own `datapack/`
  (custom biomes).
* `top`, `filler`, `filler_depth`, `underwater` are optional. The defaults depend on the biome:
  grass and dirt; sand for beaches, deserts and rivers; gravel for oceans; red sand and
  terracotta for badlands; mycelium for mushroom fields; snow for snowy peaks; netherrack/end
  stone for Nether/End biomes. `underwater` is used for the top blocks under water or lava.
* Colors missing from the mapping use the nearest mapped color (a warning lists them).

Without a biome map, the whole map uses the outside biome.

## `map.json`

Every field is optional. Unknown keys produce a warning.

```json
{
  "format": 1,
  "name": "My Map",
  "files": {
    "heightmap": "heightmap.png",
    "biomes": "biomes.png",
    "biome_mapping": "biomes.json",
    "water": "water.png",
    "lava": "lava.png",
    "structures": "structures.json",
    "datapack": "datapack/"
  },
  "origin": [-512, -512],
  "horizontal_scale": 1,
  "height": { "base_y": 0, "scale": 1 },
  "dimension": { "min_y": -64, "height": 384 },
  "sea_level": 63,
  "outside": {
    "mode": "flat",
    "ground_level": 64,
    "water_level": 63,
    "biome": "minecraft:plains"
  },
  "spawn": { "x": 0, "z": 0, "y": 80 },
  "blocks": { "default": "minecraft:stone", "bedrock": true },
  "decoration": { "features": true, "carvers": true },
  "vanilla_structures": {
    "enabled": true,
    "disabled": ["minecraft:mineshafts", "minecraft:village_desert"],
    "only": null
  }
}
```

| Key | Default | Meaning |
|---|---|---|
| `name` | ZIP file name | Name shown in the UI and logs. |
| `origin` | centered | World X, Z of pixel (0, 0). |
| `horizontal_scale` | `1` | Blocks per pixel (`2` makes a 1024 px map 2048 blocks wide). |
| `height` | see above | Value-to-Y mapping, `{base_y, scale}` or `{min_y, max_y}`. |
| `dimension` | automatic | World bottom and height (multiples of 16). |
| `sea_level` | `63` | Minecraft's sea level (mob spawning, some features). It does **not** add water; use the water map. |
| `outside.mode` | `flat` | Around the map: `flat` (ground at `ground_level`, water up to `water_level`), `void` (nothing), `extend` (border pixels extended). |
| `outside.ground_level` | `64` | Ground Y around the map (`flat`). |
| `outside.water_level` | `63` | Water surface around the map, if above the ground (`flat`). |
| `outside.biome` | `minecraft:plains` | Biome around the map (and everywhere without a biome map). |
| `spawn` | vanilla | World spawn. Without `y`, the spawn is on the surface. |
| `blocks.default` | `minecraft:stone` | Block under the surface layers. |
| `blocks.bedrock` | `true` | Bedrock at the bottom of the world. |
| `decoration.features` | `true` | Biome features: trees, grass, flowers, ores, lakes, etc. |
| `decoration.carvers` | `true` | Caves and ravines from the biomes. They don't break into the map's water and lava. |
| `vanilla_structures.enabled` | `true` | Random structures (villages, temples...). `"vanilla_structures": false` is a shorthand. |
| `vanilla_structures.disabled` | `[]` | Structures or structure sets to turn off, e.g. `minecraft:village_plains` or `minecraft:villages`. |
| `vanilla_structures.only` | all | If set, only these structures or structure sets are generated. |

The "Generate Structures" world option still applies: when it's off, no vanilla structure is
generated, nor the `structure` entries of `structures.json` (templates are still placed).

## `structures.json`

A list of structures placed at fixed coordinates (world coordinates, not pixels):

```json
[
  { "type": "template", "path": "structures/castle.nbt", "x": 120, "y": "surface", "z": -40,
    "y_offset": -1, "rotation": "clockwise_90", "mirror": "none", "entities": true },
  { "type": "template", "path": "structures/tower.nbt", "x": 300, "y": 95, "z": 12 },
  { "type": "structure", "id": "minecraft:pillager_outpost", "x": 500, "z": 500 },
  { "type": "structure", "id": "minecraft:bastion_remnant", "x": -800, "z": 200 }
]
```

* **`template`**: a structure template file from the ZIP (`.nbt`, as saved by a Structure Block
  or Minecraft's tools). Its origin (the corner with the smallest coordinates before rotation) is
  placed at `x, y, z`. `y` is a number or `"surface"` (the block above the ground, plus
  `y_offset`). `rotation`: `none`, `clockwise_90`, `clockwise_180`, `counterclockwise_90`.
  `mirror`: `none`, `left_right`, `front_back`. `entities`: also place the template's entities.
  Templates from older Minecraft versions are upgraded automatically (their `DataVersion` must be
  correct).
* **`structure`**: a registered structure (vanilla, from a mod, or from the ZIP datapack), like
  the ones `/place structure` accepts. It is generated in the chunk containing `x, z`; the exact
  position inside the chunk and the height are chosen by the structure itself. Biome rules are
  ignored. These structures are found by `/locate structure`.

`type` can be omitted: an entry with `path` is a template, one with `id` a structure.

## `datapack/`

A normal datapack folder (with its `pack.mcmeta`), copied into the world's `datapacks/` folder
and enabled when the world is created. Use it for custom biomes (`data/<ns>/worldgen/biome/`),
structures, features, loot tables, and so on.

Minecraft treats worldgen content added by datapacks as experimental, so creating a world with
custom biomes shows the vanilla "experimental settings" warning.

## Validation

The ZIP is checked when it is selected (or when a dedicated server starts). Errors block the
creation: missing heightmap, wrong bit depth, size mismatch, invalid JSON, unknown biome, block
or structure, invalid datapack... Warnings (unmapped colors, unknown keys, terrain cut by the
world limits) are shown but don't block.
