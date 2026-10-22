# Map Generator

A Minecraft 26.3 mod that adds a **Map from ZIP** world type: instead of procedural generation, the
world is built from a predefined map packed in a ZIP file:

* a **heightmap** (8-bit or 16-bit grayscale),
* a **biome map** with a color → biome mapping (vanilla, modded or custom biomes),
* **water** and **lava** maps giving fluid surfaces (lakes, seas, lava pools),
* a **world config** (`map.json`): height mapping, world height, what's around the map, spawn,
  decoration, which vanilla structures to keep,
* a **structure list**: structure templates from the ZIP or registered structures (bastion,
  outpost...) at given coordinates,
* an optional **datapack** (custom biomes, etc.) installed in the world.

The format is described in [docs/FORMAT.md](../docs/FORMAT.md). `example/` contains a script that
builds two sample maps (`example/example.zip`, and `example/example-tall.zip` with 16-bit maps and a
700-block-high world).

## Mod loaders

| Loader | Status |
|---|---|
| Fabric (+ Fabric API) | Supported |
| NeoForge | Supported |
| Quilt | Not tested; Quilt runs Fabric mods, so the Fabric jar should work if Quilt supports 26.3 |
| Forge | Not supported: there is no Forge for 26.3 in the MultiLoader toolchain used here |
| LiteLoader | Not possible: LiteLoader stopped at Minecraft 1.12.2 |

Both jars share the same code (`common/` and `core/`); only registration differs.

## Using it

### Singleplayer

1. Put your map ZIPs in `.minecraft/mapgen/` (optional: you can also pick any file).
2. **Create New World → World → World Type: Map from ZIP → Customize**.
3. Choose the ZIP: type its path, use **Browse...**, drop the file on the screen, or pick one from
   the maps folder. The map is checked immediately; errors and warnings are listed.
4. **Done**, then **Create New World**. Creating a world with this world type and no ZIP opens the
   selection screen instead.

The ZIP is copied into the world (`<world>/mapgen/<id>.zip`), so the world doesn't depend on the
original file and can be moved or shared. Its datapack, if any, goes into `<world>/datapacks/`.

### Dedicated server

In `server.properties`, before the world is created:

```properties
level-type=mapgen:map
generator-settings={"pack":"maps/my_map.zip"}
```

The path is relative to the server folder. On first start the ZIP is validated (the server stops
with the list of problems if it is invalid), copied into the world, and its datapacks installed.

## Building

Run these commands from this `mod/` folder.


Gradle must run on Java 25 (set `JAVA_HOME`), which Loom requires. The core-only build and tests also
work on Java 21.

```sh
./gradlew build          # jars in fabric/build/libs and neoforge/build/libs
./gradlew -PcoreOnly :core:test   # format parsing tests only, no Minecraft download
./gradlew :fabric:runClient       # or :neoforge:runClient, :fabric:runServer, ...
python3 example/make_example.py   # rebuild the example maps
```

### Project layout

* `core/`: Minecraft-independent code: ZIP reading, PNG decoding (8/16-bit), `map.json` /
  `biomes.json` / `structures.json` parsing and validation, terrain sampling. Unit tested.
* `common/`: the Minecraft side shared by both loaders: chunk generator, biome source, structure
  placement, world creation screen, dedicated server support (mixins).
* `fabric/`, `neoforge/`: loader entry points and registration.
