# World of Warcraft extractor

Extracts World of Warcraft terrain from a local game installation and writes map ZIPs (see
[the format](../../docs/FORMAT.md)) for the Minecraft Map Generator [mod](../../mod/): a 16-bit
heightmap, water and lava maps, a biome map with its `biomes.json`, and `map.json`. Floating-island maps also get `bottom.png`, and
underground maps `ceiling.png`. With `--structures`, the buildings come along as structure templates.

It reads the game's CASC storage directly. You don't need Windows, a CASC explorer or a listfile, and it
has no runtime dependencies.

## Requirements

- Node.js 22.18 or newer (it runs the TypeScript sources directly).
- A World of Warcraft installation (retail, 8.2+ storage format). Steam/Proton, Lutris and Wine
  prefixes are detected automatically. Otherwise, pass `--wow <dir>` or set `WOW_DIR`.
- Internet access the first time: the DB2 column layouts come from
  [WoWDBDefs](https://github.com/wowdev/WoWDBDefs) and are cached in `~/.cache/warcraft-map-extractor`.
  After a game patch they are downloaded again only if needed.

## Usage

Run the commands from this folder (`extractors/warcraft/`).

```sh
node src/cli.ts list                         # maps that have terrain, then asks for filters
node src/cli.ts list skywall                 # only the matching maps
node src/cli.ts zones Azeroth                # zones of a map (ID, internal name, name)

node src/cli.ts extract Azeroth --zone ElwynnForest            # Elwynn Forest, 2 yards per block
node src/cli.ts extract Azeroth --zone ElwynnForest --zone Westfall --scale 1
node src/cli.ts extract Kalimdor --scale 4 -o kalimdor.zip     # a whole continent
node src/cli.ts extract Northrend --tiles 20,20,25,25          # a range of ADT tiles
node src/cli.ts extract 754 --name "Throne of the Four Winds"  # floating islands → Throne_of_the_Four_Winds.zip
node src/cli.ts extract Deephome                               # underground, with a ceiling
node src/cli.ts extract Azeroth --zone ElwynnForest --structures  # with Stormwind, Goldshire...
```

`pnpm start <command> ...` works too. A map is a map ID (`754`), directory (`SkywallRaid`) or name
(in the game's language). IDs are checked first: many recent maps only have an ID, which is also
their directory. A zone is an area ID, internal name (`ElwynnForest`) or localized name.

In a terminal, `list` prints every map and then keeps asking for a filter: words match the directory
or the name, a number matches the map ID, and an empty answer quits. `list <filter>` prints the
matching maps only.

| Option | Default | Meaning |
|---|---|---|
| `-o, --output <file>` | `<name>.zip` or `<map>[-<zone>].zip` | Output ZIP. |
| `--name <name>` | the map or zone name | World name, also used for the default ZIP name. Useful for maps known by an internal name, like `SkywallRaid`. |
| `--type <type>` | `auto` | `normal`, `floating` (islands in the void, with `bottom.png`) or `underground` (a cave world, with `ceiling.png`). `auto` detects them (see [Special maps](#special-maps)). |
| `--zone <area>` | whole map | Crops to the chunks of that area and its sub-areas. Repeat it to combine zones. |
| `--margin <yards>` | `64` | Extra terrain kept around `--zone`. |
| `--tiles <x0,y0,x1,y1>` | all | Crops to a range of ADT tiles (0 to 63, inclusive). |
| `--scale <yards>` | `2` | Yards per block horizontally. `1` is real scale: a WoW character is about as tall as a Minecraft player. |
| `--vertical-scale <yards>` | `--scale` | Yards per block vertically. A smaller value exaggerates the relief. |
| `--sea-level <y>` | `63` | Minecraft Y of WoW's sea level (height 0). |
| `--min-height <yards>` | `-256` | Deeper sea beds are raised to this height, so the world isn't needlessly deep. |
| `--missing <ocean\|void>` | `ocean` for open worlds, `void` for instances | What fills places without terrain, inside the map and around it. |
| `--ocean-depth <blocks>` | `32` | Depth of that ocean. |
| `--biomes <file.json>` | | Biome overrides (see below). |
| `--vanilla-structures` | off | Lets Minecraft add villages, temples and so on. |
| `--carvers` | off | Lets Minecraft dig caves and ravines into the terrain. |
| `--structures` | off | Also converts the buildings into structure templates (see [Buildings](#buildings)). |
| `--force` | | Skips the size checks without asking: images above 100 million pixels, heavy structures. |

### Sizes

At the default scale, a zone is a few thousand blocks wide. A continent is 10,000 to 16,000 blocks
wide, which is too big for one image (the extractor refuses above 100 million pixels unless you
pass `--force`). Use `--zone`, `--tiles` or `--scale 4` for whole continents. Extraction is fast:
Kalimdor at scale 4 (4133 × 6267 pixels) takes about 4 seconds and 700 MB of memory. Minecraft
also needs memory to load the images, so stay under about 8192 pixels per side.

## What's in the ZIP

| File | Content |
|---|---|
| `heightmap.png` | 16-bit ground height, from the real terrain mesh: each pixel samples WoW's triangles, so any scale works. |
| `water.png`, `lava.png` | Liquid surfaces (rivers, lakes, oceans; magma). Slime counts as water. Liquids under the ground are dropped. |
| `biomes.png`, `biomes.json` | One color per WoW area (sub-zones included), mapped to a Minecraft biome. Open water gets ocean/river biomes that match the climate of the coast (frozen, warm, temperate), deep oceans included. |
| `bottom.png` | Floating maps only: the underside of each island (see below). |
| `ceiling.png` | Underground maps only: where the solid roof starts. |
| `map.json` | Height mapping (`base_y` + `scale` of 1/16 block), sea level 63, world border, the ocean or void around the map. Lakes and springs are turned off so they don't spill onto the terrain, and so are carvers (caves, ravines) unless you pass `--carvers`. Special maps also get a spawn point. |
| `structures.json`, `structures/*.nbt` | With `--structures`: the buildings, as templates placed at world coordinates. |
| `extractor.json` | Not read by the mod: game version, scale, bounds in yards, tile range, and the list of areas with their color and biome. |

Heights keep their proportions: by default 1 block is 2 yards both ways, and WoW's sea level is
at Y=63. The mod sizes the world to fit the map (Hyjal and the Storm Peaks reach about Y 1000 at
scale 2).

## Special maps

Some maps aren't a landscape under the sky. `--type auto` (the default) recognizes them:

- **Floating**: the terrain is all holes and the ground is made of models (WMO), like the Throne of
  the Four Winds (`SkywallRaid`), the Vortex Pinnacle or the Eye of Eternity. The heightmap is the top
  of the models and `bottom.png` their underside. Model collision meshes are often just a floor, so
  every island also gets a tapered underside, up to 32 blocks deep in its middle. The map floats in
  the void, without bedrock.
- **Underground**: models roof a large part of the terrain, like Deephome, Zaralek Cavern, the
  underground of Khaz Algar or Ahn'kahet. The heightmap is the terrain and `ceiling.png` the lowest
  model surface facing down above it (at least 3 yards up). Places without a model above get the
  highest ceiling, so the cave stays closed. The map sits in the void.

Both get a spawn point on dry ground close to the center (vanilla would pick the void or the top of
the ceiling). Only the solid (collidable) faces of the models are used: decorations like clouds and
the wind streams of the Throne of the Four Winds are left out. If the detection is wrong, force it
with `--type`.

## Buildings

`--structures` converts the buildings and other WMO models (cities, houses, towers, bridges, ships,
mines...) into structure templates:

- Their faces are turned into blocks at the map's scale. Buildings are hollow shells: walls, floors and
  roofs. Parts buried more than 2 blocks under the terrain are dropped, since nobody can see them.
- Each block takes the color of the face's texture, matched to the closest of about 90 building blocks
  (stone, bricks, planks, terracotta, concrete, copper...). Stormwind keeps its white walls and its
  blue, red and purple roofs.
- The templates are cut into 16 × 16 pieces aligned on Minecraft chunks: the mod pastes templates
  chunk by chunk, and a large template would be read again for every chunk it touches.
- At 2 yards per block, Elwynn Forest with Stormwind is about 1.1 million blocks in 1,400 templates
  (3.5 MB). Small details (fences, lamps, thin beams) are lost at that scale; `--scale 1` keeps more.

Heavy structures slow world creation and generation down, and need memory. When the structures go
over 2 million blocks, 5,000 template files, 100 MB, or 500,000 blocks for one building, the
extractor shows a warning:

- in a terminal, it asks whether to write them anyway;
- otherwise (scripts, pipes) it stops;
- `--force` writes them without asking.

On floating maps, the models are already the islands, so `--structures` is ignored. On underground
maps, the large models (the cave itself) stay the ceiling and the others become structures.

Furniture and props inside buildings (M2 doodads) aren't included, and interiors aren't hollowed out
of the terrain: a mine dug into a hill stays filled.

## Biomes

Each area gets a biome in this order:

1. your overrides (`--biomes`), checked from the area up through its parent zones;
2. the built-in table of zones (classic to The War Within): Elwynn Forest is `forest`, Tanaris
   `desert`, Winterspring `snowy_taiga`, Zangarmarsh `mushroom_fields`, the Burning Steppes
   `badlands` with blackstone and basalt...;
3. keywords in the internal names (`Frost`, `Desert`, `Swamp`, `Forest`, `Peak`...);
4. `minecraft:plains`.

An override file maps internal zone names or area IDs (see `zones` or `extractor.json`) to a biome
or to a full `biomes.json` entry:

```json
{
  "ElwynnForest": "minecraft:birch_forest",
  "Goldshire": "minecraft:meadow",
  "1519": { "biome": "minecraft:plains", "top": "minecraft:stone_bricks" }
}
```

You can also edit `biomes.json` in the ZIP, or open the ZIP in the web editor (`site/`).

## Limitations

- Without `--structures`, normal maps only have the terrain and liquids: cities like Stormwind come
  out as bare ground with their canals. Doodads (M2: rocks, trees, props) are never extracted.
- A heightmap has one ground level per column: tunnels, bridges and building interiors can't be
  represented. On floating maps, a building on an island becomes a solid block.
- Maps made of a single model (most dungeons and raids) have no terrain tiles and aren't supported.
- Terrain holes (cave and building entrances) are filled, except on floating maps.
- Liquids inside buildings (WMO liquids) are missing.
- Tiles that are encrypted (unreleased content) or not downloaded are skipped with a warning, and
  are filled like missing terrain.
- Area IDs are set per 33-yard chunk, so biome borders follow a coarse grid. The mod's
  `biome_transition` (set to 8) softens them.

## How it works

1. `.build.info` → build config → encoding and root files in the local CASC storage
   (`Data/data/*.idx` and `data.NNN`, BLTE-encoded).
2. `Map.db2`, `AreaTable.db2`, `LiquidType.db2` and `LiquidMaterial.db2` (WDC5) give the maps,
   areas and liquid types. Their columns are named with WoWDBDefs.
3. The map's WDT lists its 64 × 64 ADT tiles. Each root ADT has 16 × 16 chunks of 145 height
   vertices (`MCVT`), an area ID, and liquid layers (`MH2O`).
4. The terrain is sampled on a regular grid at the chosen scale and written as PNGs.
5. For special maps, the obj0 ADTs list the WMO placements (`MODF`). The collidable triangles of
   the WMO groups are placed on the map and rasterized into top, bottom and ceiling surfaces.
6. For `--structures`, the WMO faces are voxelized; each material's texture (BLP) gives the block
   color. The blocks are written as gzipped NBT structure templates.

## Development

```sh
pnpm install       # TypeScript, Node types and Ajv, for checks only
pnpm test          # node:test unit tests
pnpm typecheck
```

`test/schemas.test.ts` writes map ZIPs from a synthetic terrain (normal, floating, underground,
with structures) and validates their `map.json`, `biomes.json` and `structures.json` against
[`docs/schemas`](../../docs/schemas/). The `extractors.yml` workflow runs the tests when this folder
or the schemas change.

The sources are in `src/`: `casc/` (storage), `formats/` (BLTE, DB2, WDT, ADT, WMO, BLP), `terrain.ts`
(sampling), `models.ts` (WMO rasterization), `structures.ts` (buildings to templates), `palette.ts` (block
colors), `extract.ts` (cropping, map type detection), `biomes.ts`, `mapPack.ts` (map ZIP), `output/`
(PNG, ZIP, NBT writers), `cli.ts`.
