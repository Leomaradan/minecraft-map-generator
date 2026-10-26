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

A map generates the overworld by default; it can also replace the Nether, the End or a mod's
dimension, or add a new dimension, and one ZIP can hold a map per dimension.

The format is described in [docs/FORMAT.md](../docs/FORMAT.md). `example/` contains a script that
builds three sample maps: `example/example.zip`, `example/example-tall.zip` (16-bit maps and a
700-block-high world) and `example/example-dimensions.zip` (the overworld, the Nether and a new
dimension).

## Minecraft versions

This branch (`main`) builds the mod for Minecraft 26.3. Minecraft 26.1 (26.1.x) and 26.2 are built
from the long-lived [`mc/26.1-26.2`](https://github.com/Leomaradan/minecraft-map-generator/tree/mc/26.1-26.2) branch: 26.3 rewrote the world
generation API (density functions, carvers, structure starts, biome resolvers) and replaced GLFW
with SDL3, so the generator code differs between the two branches. Fixes to the generator are
ported by hand from one branch to the other; `core/` is the same on both.

The map ZIP format is the same for every version, but a map's `datapack/` follows Minecraft's own
format: custom biomes written for 26.3 don't load on 26.1 or 26.2, and the other way round. A
datapack can support both with an overlay, as the example maps do: their files are in the
26.1/26.2 format, and an `mc26_3` overlay (applied from data pack format 121) holds the 26.3
versions. The example ZIPs are the same on both branches and work on 26.1, 26.2 and 26.3.

## Mod loaders

| Loader | Status |
|---|---|
| Fabric (+ Fabric API) | Supported |
| NeoForge | Supported |
| Quilt | Not tested; Quilt runs Fabric mods with the Fabric jar. The jar accepts Fabric Loader 0.19.3 and later (what Fabric API requires), so stable Quilt (reported as Fabric Loader 0.19.3) is accepted |
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

### Continuous integration

* `.github/workflows/mod.yml` builds and tests the mod on every change under `mod/`. It publishes two
  workflow artifacts, `mapgen-fabric` and `mapgen-neoforge`, each holding only the mod jar
  (`mapgen-<loader>-<minecraft version>-<version>.jar`). The sources and javadoc jars stay in the
  local `build/libs` folders.
* `.github/workflows/release.yml` runs on a `v*` tag matching `version` in `gradle.properties`
  (`v1.0.1` for `version=1.0.1`). It publishes the same two jars with
  [mc-publish](https://github.com/Kira-NT/mc-publish): one version per loader on CurseForge and
  Modrinth, and a GitHub release with both jars and the example map ZIPs. It needs the repository
  variables `CURSEFORGE_PROJECT_ID` and `MODRINTH_PROJECT_ID` and the secrets `CURSEFORGE_TOKEN` and
  `MODRINTH_TOKEN`; a platform without a token is skipped.

Artifact names come from `mod_id` in `gradle.properties`, so the workflows can be copied to another
mod built from the same template without changes.

### Project layout

* `core/`: Minecraft-independent code: ZIP reading, PNG decoding (8/16-bit), `map.json` /
  `biomes.json` / `structures.json` parsing and validation, terrain sampling. Unit tested.
* `common/`: the Minecraft side shared by both loaders: chunk generator, biome source, structure
  placement, world creation screen, dedicated server support (mixins).
* `fabric/`, `neoforge/`: loader entry points and registration.
