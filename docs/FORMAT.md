# Map ZIP format

A map is a ZIP file. Only `map.json` and a heightmap are required. If everything is inside a
single top-level folder in the ZIP, that folder is ignored.

The file names below are the defaults. Every file except `map.json` can have another name or be in
a sub-folder: name it in `map.json` under `files` (see [File names](#file-names)). For example, a
ZIP without `heightmap.png` is valid when `map.json` contains
`"files": {"heightmap": "terrain.png"}`.

```
map.json            REQUIRED  world configuration (always at the root, under this name)
heightmap.png       REQUIRED  ground height, 8-bit or 16-bit grayscale (or the file named in files.heightmap)
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

## File names

`files` in `map.json` gives the path, inside the ZIP, of each file. Each key is optional and
defaults to the name in the list above:

```json
"files": {
  "heightmap": "maps/terrain-16bit.png",
  "biomes": "maps/regions.png",
  "biome_mapping": "maps/regions.json",
  "water": "maps/sea.png",
  "lava": "maps/magma.png",
  "structures": "structures.json",
  "datapack": "datapack/"
}
```

* A file named in `files` must be in the ZIP, otherwise the map is rejected.
* A file left to its default name is only read if the ZIP contains it; the heightmap is the
  exception, since a map always needs one.
* When a file has another name, the default name is ignored: with `"water": "sea.png"`, a
  `water.png` in the ZIP is not read.
* Paths use `/` (a `\` is accepted and converted); a leading `/` is ignored.

## JSON Schemas

`map.json`, `biomes.json` and `structures.json` have JSON Schemas in [`docs/schemas/`](schemas/).
Reference them with a `$schema` key to get completion and validation in your editor:

```json
{ "$schema": "https://raw.githubusercontent.com/Leomaradan/minecraft-map-generator/main/docs/schemas/map.schema.json" }
```

The same goes for `biomes.schema.json` and `structures.schema.json`. The mod ignores the
`$schema` key.

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

You can also set the height yourself: `"dimension_parameter": {"min_y": -64, "height": 1024}` (or
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
  "$schema": "https://raw.githubusercontent.com/Leomaradan/minecraft-map-generator/main/docs/schemas/biomes.schema.json",
  "#1E3C8C": "minecraft:ocean",
  "#5DBB4F": "plains",
  "240,220,130": "minecraft:beach",
  "#C850E6": {
    "biome": "example:crystal_fields",
    "top": "minecraft:moss_block",
    "filler": "minecraft:dirt",
    "filler_depth": 4,
    "underwater": "minecraft:clay"
  },
  "#22662A": {
    "biome": "minecraft:forest",
    "biome_river": "minecraft:river",
    "river_width": 3,
    "biome_border": { "biome": "minecraft:birch_forest", "top": "minecraft:podzol" },
    "border_width": 6,
    "biome_mountain": "minecraft:grove",
    "mountain_y": 140,
    "biome_underground": "minecraft:lush_caves",
    "underground_depth": 12
  },
  "#E0E0E0": {
    "biome": "minecraft:taiga",
    "snow": { "min_y": 400 },
    "biome_mountain": { "biome": "minecraft:grove", "snow": true },
    "mountain_y": 600
  },
  "#8A8F5A": {
    "biome": "minecraft:old_growth_pine_taiga",
    "top": {
      "blocks": [{ "block": "minecraft:podzol", "weight": 3 }, "minecraft:coarse_dirt"],
      "method": "perlin",
      "scale": 12
    },
    "filler": ["minecraft:dirt", "minecraft:dirt", "minecraft:gravel"],
    "features": { "disabled": ["minecraft:forest_rock"] },
    "carvers": { "cave": true, "canyon": false }
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
  `top`, `filler` and `underwater` take a block id or a mix of blocks (see
  [Block lists](#block-lists)).
* `snow` is optional: where snow and ice may form in this biome (see [Snow](#snow)).
* `features` and `carvers` are optional: they replace `decoration.features` and
  `decoration.carvers` of `map.json` in this biome (see [Decoration per biome](#decoration-per-biome)).
* Colors missing from the mapping use the nearest mapped color (a warning lists them).

Without a biome map, the whole map uses the outside biome.

### Substitute biomes

An entry can replace its biome in some places. Each substitute is optional. Its value is a biome
id, or an object with `biome` and its own `top`, `filler`, `filler_depth`, `underwater`, `snow`,
`features` and `carvers`.
A substitute given as a plain id uses the default surface blocks of that biome, not the
entry's blocks. A substitute uses the `snow`, `features` and `carvers` of its entry, unless it
sets its own (in its object form).

| Key | Used | Setting |
|---|---|---|
| `biome_river` | where the water map puts water over the ground (not lava), and on the land within `river_width` blocks of it | `river_width`, 0 to 64, default `4` (`0`: the water only) |
| `biome_mountain` | where the ground is at or above `mountain_y` | `mountain_y`, default `sea_level + 64` |
| `biome_border` | within `border_width` blocks of a pixel of another entry | `border_width`, 1 to 64, default `4` |
| `biome_underground` | from `underground_depth` blocks below the ground down to the bottom (a biome id only) | `underground_depth`, default `16` |

When several substitutes apply at the surface, `biome_river` wins over `biome_mountain`, which
wins over `biome_border`. `biome_underground` applies below the surface whatever the surface
biome is, so cave biomes like `minecraft:lush_caves` or `minecraft:dripstone_caves` get their
features (moss, dripstone...). The edges of the map image don't count as a border.

### Biome transitions

Borders between biomes don't follow the pixel edges exactly. They are shifted by a smooth noise,
up to `biome_transition` blocks (`map.json`, default `4`), so a straight line between a desert
and a savanna becomes a natural curve, about `7.5 × biome_transition` blocks long. The noise is
the same in every world made from the map. Set `"biome_transition": 0` to keep the exact pixel
borders.

The noise is smooth enough that a band 2 pixels wide drawn in the biome map stays connected; a
1-pixel band (with `horizontal_scale` 1) can have small gaps. Rivers made with the water map and
`biome_river` are not shifted.

Grass, foliage and water colors are also blended by the game's "Biome Blend" video setting.
That's why some transitions (meadow to swamp) already looked smooth while changes of surface
block (sand to grass) did not.

### Snow

Minecraft's biomes get colder with height, from `sea_level + 17` up: plains turn snowy around
Y=600, taigas around Y=160. On a tall map, most of the terrain ends up under snow, and water
freezes. `snow` limits where snow layers and ice form, in `map.json` for the whole map and in
`biomes.json` for one entry or substitute (which then ignores the one of `map.json`):

| Value | Snow and ice |
|---|---|
| `true` (default) | wherever the biome is cold enough, as in vanilla |
| `false` | never |
| `{ "min_y": 400, "max_y": 900 }` | only from `min_y` to `max_y` (both optional), where the biome is cold enough |

`snow` only removes snow: it can't make a warm biome snowy. It applies when the world is
generated (the `freeze_top_layer` feature) and to snowfall during the game. Snow blocks from the
surface (`top`, snowy peaks) and snowy biome features (ice spikes, powder snow) are not affected.
Where snow can't settle, snowflakes still fall during a storm, as the client doesn't know the
setting.

### Decoration per biome

`features` and `carvers` take the same values as `decoration.features` and
`decoration.carvers` in `map.json`. On an entry or a substitute object, they replace the values
of `map.json` there (they are not combined):

```json
{
  "#5DBB4F": { "biome": "minecraft:plains", "features": { "disabled_steps": ["lakes"] } },
  "#A0A0A0": { "biome": "minecraft:stony_peaks", "carvers": false, "features": false }
}
```

* Features are checked where they are placed: an ore placed underground uses the `features` of
  the slot there (`biome_underground` uses `map.json`'s, as it can't have its own). Features
  that don't check their biome (rare) can't be filtered per biome.
* Carvers are picked per chunk, from the biome at the north-west corner of the chunk where the
  cave starts, like in vanilla. A cave can cross into a neighbor biome that has no carvers.

## Block lists

Every block of `biomes.json` (`top`, `filler`, `underwater`) and `blocks.default` in `map.json`
can be a single block id, or a mix of blocks:

```json
"top": "minecraft:grass_block"
"top": ["minecraft:grass_block", "minecraft:coarse_dirt"]
"top": [{ "block": "minecraft:grass_block", "weight": 3 }, "minecraft:coarse_dirt"]
"top": {
  "blocks": [{ "block": "minecraft:grass_block", "weight": 3 }, "minecraft:coarse_dirt"],
  "method": "perlin",
  "scale": 12
}
```

* A list picks one of its blocks for each position. `weight` is the relative probability
  (default `1`, a plain id counts as `1`): above, grass covers about 3/4 of the surface.
* The object form chooses how blocks are picked with `method`:

  | `method` | Result |
  |---|---|
  | `random` (default) | each block is picked independently: a speckled mix |
  | `perlin` | smooth 3D noise: the blocks form patches about `scale` blocks wide (default `16`) |

* The same position always gives the same block, in every world made from the map. Each layer
  has its own pattern, so a `top` and a `filler` with the same blocks don't line up.
* A block that doesn't exist is reported when the map is loaded, like a single block id.

## `map.json`

Every field is optional. Unknown keys produce a warning.

```json
{
  "$schema": "https://raw.githubusercontent.com/Leomaradan/minecraft-map-generator/main/docs/schemas/map.schema.json",
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
  "dimension_parameter": { "min_y": -64, "height": 384 },
  "sea_level": 63,
  "biome_transition": 4,
  "outside": {
    "mode": "flat",
    "ground_level": 64,
    "water_level": 63,
    "biome": "minecraft:plains",
    "border": true
  },
  "spawn": { "x": 0, "z": 0, "y": 80 },
  "blocks": { "default": "minecraft:stone", "bedrock": true },
  "snow": { "min_y": 300 },
  "decoration": {
    "features": {
      "disabled": ["minecraft:lake_lava_surface", "minecraft:lake_lava_underground"],
      "disabled_steps": ["fluid_springs"]
    },
    "carvers": true
  },
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
| `files` | default names | Paths of the other files in the ZIP. See [File names](#file-names). |
| `dimension_parameter` | automatic | World bottom and height (multiples of 16). |
| `sea_level` | `63` | Minecraft's sea level (mob spawning, some features). It does **not** add water; use the water map. |
| `biome_transition` | `4` | Biome borders are shifted by up to this many blocks (0 to 64) so they look natural; `0` keeps pixel borders. See [Biome transitions](#biome-transitions). |
| `outside.mode` | `flat` | Around the map: `flat` (ground at `ground_level`, water up to `water_level`), `void` (nothing), `extend` (border pixels extended). |
| `outside.ground_level` | `64` | Ground Y around the map (`flat`). |
| `outside.water_level` | `63` | Water surface around the map, if above the ground (`flat`). |
| `outside.biome` | `minecraft:plains` | Biome around the map (and everywhere without a biome map). |
| `outside.border` | `true` | Puts a world border around the map when the world is created, so players can't leave it. The border is a square: it fits the longest side of the map, centered on it, so a non-square map has some outside terrain on its short sides. Without a `spawn`, a spawn that vanilla picked outside the border moves to the center of the map. Change it later with `/worldborder`. |
| `spawn` | vanilla | World spawn. Without `y`, the spawn is on the surface. |
| `blocks.default` | `minecraft:stone` | Blocks under the surface layers: a block id or a [block list](#block-lists). |
| `blocks.bedrock` | `true` | Bedrock at the bottom of the world. |
| `snow` | `true` | Where snow layers and ice may form: `true`, `false` or `{min_y, max_y}`. See [Snow](#snow). |
| `decoration.features` | `true` | Biome features: trees, grass, flowers, ores, lakes, etc. `true`, `false`, or a filter (below). An entry of `biomes.json` can override it. |
| `decoration.features.enabled` | `true` | `false` turns every feature off. |
| `decoration.features.disabled` | `[]` | Placed features to skip, e.g. `minecraft:lake_lava_surface`. |
| `decoration.features.disabled_steps` | `[]` | Generation steps to skip entirely (see below). |
| `decoration.features.only` | all | If set, only these placed features are generated. |
| `decoration.carvers` | `true` | Caves and ravines from the biomes: `true`, `false`, or `{"cave": bool, "canyon": bool}` (each `true` by default). They don't break into the map's water and lava. An entry of `biomes.json` can override it. |
| `vanilla_structures.enabled` | `true` | Random structures (villages, temples...). `"vanilla_structures": false` is a shorthand. |
| `vanilla_structures.disabled` | `[]` | Structures or structure sets to turn off, e.g. `minecraft:village_plains` or `minecraft:villages`. |
| `vanilla_structures.only` | all | If set, only these structures or structure sets are generated. |

Features are identified by their placed feature id, as listed in the biome files of Minecraft
(`data/minecraft/worldgen/biome/*.json`) or `/place feature`. Some useful ones:
`minecraft:lake_lava_surface`, `minecraft:lake_lava_underground` (lava lakes),
`minecraft:spring_water`, `minecraft:spring_lava` (springs), `minecraft:ore_diamond`... Ids that
no biome uses are ignored. The generation steps are `raw_generation`, `lakes`,
`local_modifications`, `underground_structures`, `surface_structures`, `strongholds`,
`underground_ores`, `underground_decoration`, `fluid_springs`, `vegetal_decoration` and
`top_layer_modification`. For example, `"disabled_steps": ["lakes", "fluid_springs"]` removes the
lava lakes and the springs, which would spill onto a map's hand-made terrain.

The "Generate Structures" world option still applies: when it's off, no vanilla structure is
generated, nor the `structure` entries of `structures.json` (templates are still placed).

## `structures.json`

Structures placed at fixed positions. `x` and `z` are pixels of the heightmap: the structure goes
to the center of that pixel in the world, wherever `origin` and `horizontal_scale` put it.

```json
{
  "$schema": "https://raw.githubusercontent.com/Leomaradan/minecraft-map-generator/main/docs/schemas/structures.schema.json",
  "structures": [
    { "type": "template", "path": "structures/castle.nbt", "x": 120, "y": "surface", "z": 40,
      "y_offset": -1, "rotation": "clockwise_90", "mirror": "none", "entities": true },
    { "type": "template", "path": "structures/tower.nbt", "x": 300, "y": 95, "z": 12 },
    { "type": "structure", "id": "minecraft:village_desert", "x": 547, "z": 2287 },
    { "type": "structure", "id": "mymod:castle", "fallback_id": "minecraft:pillager_outpost", "x": 800, "z": 200 }
  ]
}
```

* With `"coordinates": "world"` next to `"structures"`, `x` and `z` are world block
  coordinates instead.
* A position outside the heightmap gives a warning (the structure is still placed).
* `y` is always a world Y.
* A file that is only the list (the format before `"structures"` existed) still works, with a
  warning. Its positions are world coordinates, as they were then.

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
  When `id` doesn't exist (a structure from a mod that isn't installed), `fallback_id` is used
  instead; without a `fallback_id` that exists, the structure is skipped. Both cases are reported
  as warnings when the map is loaded.

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
