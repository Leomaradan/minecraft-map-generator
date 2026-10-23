# CLAUDE.md

This file guides Claude Code (claude.ai/code) when it works in this repository.

## What this is

There are two products, both built around one **map ZIP format**. A map ZIP holds a heightmap, a
biome map, water and lava maps, `map.json`, structures and an optional datapack.

- `mod/`: a Minecraft 26.3 mod (Fabric and NeoForge) that adds a "Map from ZIP" world type. Minecraft 26.1 and 26.2 are built from the long-lived `mc/26.1-26.2` branch. Never merge that branch into `main`. Generator fixes are ported by hand in both directions, because 26.3 rewrote the world generation API. See that branch's CLAUDE.md for the differences.
- `site/`: the GitHub Pages site. It has a landing page and a browser editor that creates and edits map ZIPs.
- `docs/FORMAT.md` and `docs/schemas/*.schema.json`: the format spec. Both products depend on it.

## Commands

### Mod (run from `mod/`)

Gradle must run on Java 25, because Loom requires it. The core module targets Java 21.

```sh
./gradlew build                     # all modules + tests; jars in fabric/build/libs, neoforge/build/libs
./gradlew -PcoreOnly :core:test     # core tests only, no Minecraft download (fast; works on Java 21)
./gradlew -PcoreOnly :core:test --tests 'com.leomaradan.mapgen.core.TerrainSamplerTest'   # one test class
./gradlew :fabric:runClient         # or :neoforge:runClient, :fabric:runServer
python3 example/make_example.py     # regenerate example/example.zip and example-tall.zip
```

`-PcoreOnly` stops `settings.gradle` from including `common`, `fabric` and `neoforge`. Gradle then never resolves the Loom or ModDevGradle plugins.

### Site (run from `site/`)

```sh
pnpm install
pnpm dev             # Vite dev server (landing at /minecraft-map-generator/, editor at /minecraft-map-generator/editor/)
pnpm test            # vitest run (node environment)
pnpm vitest run src/lib/rivers.test.ts   # one test file
pnpm typecheck       # tsc --noEmit
pnpm build           # typecheck + vite build to dist/
```

The site uses pnpm. Its version is pinned in `packageManager` in `site/package.json`, and CI installs with `--frozen-lockfile`. Commit `pnpm-lock.yaml` with any dependency change, and don't add a `package-lock.json`.

## Architecture

### Mod: `core` / `common` / loaders

- `mod/core/`: plain Java code with no Minecraft dependency. It contains:
  - `MapPackReader` / `MapPack`: reads the ZIP, resolves file names from `files` in `map.json`, collects errors and warnings into a `ValidationReport`.
  - `MapConfig`: parses `map.json`. `BiomeMapping` parses `biomes.json`. `StructureEntry` parses `structures.json`.
  - `png/PngImage`: decodes 8-bit and 16-bit PNGs.
  - `HeightMapping`: maps a raster value to a Y level. `DimensionLayout` computes the world height.
  - `TerrainSampler`: bilinear sampling of height and fluids.
  - `BlockPalette`, `DistanceField`, `PerlinNoise`: helpers.

  All format logic and validation belongs here, and it is unit tested (JUnit 5 + JaCoCo for Sonar). Gson is `compileOnly` because Minecraft bundles it. Core is not published as a separate jar: its sources are compiled into each loader jar, as with `common` (see `multiloader-loader.gradle`).
- `mod/common/`: the Minecraft side, shared by both loaders.
  - `world/MapChunkGenerator`: the main generator (terrain, fluids, decoration, carvers, structures). `MapBiomeSource`, `MapStructures`, `NoiseCaves` and `MapSettings` support it.
  - `world/MapWorlds` and `pack/MapPacks`: copy the ZIP into `<world>/mapgen/<id>.zip`, install datapacks, and install the overworld-height datapack.
  - `client/`: the world creation UI (`SelectMapScreen`, native file dialog).
  - `server/DedicatedServerMaps`: reads `generator-settings={"pack":...}` from `server.properties`.
  - `mixin/`: listed in `mapgen.mixins.json`, split into common, client and server mixins.
  - `platform/Services` + `IPlatformHelper`: loader abstraction through `ServiceLoader`. Each loader provides its implementation in `META-INF/services`.
- `mod/fabric/`, `mod/neoforge/`: only the entry points and registration.
- `mod/build-logic/`: the MultiLoader convention plugins (`multiloader-common`, `multiloader-loader`). If you add a property to `gradle.properties` and use it in resource files, you must also add it to `expandProps` in `multiloader-common.gradle`.

### Site: Vite multi-page React app

- There are two entry points, set in `vite.config.ts`: `index.html` loads `src/landing/` and `editor/index.html` loads `src/editor/`. `base` is `/minecraft-map-generator/`.
- `src/lib/`: pure logic, with no DOM or React, tested with vitest.
  - `project.ts`: the editor's in-memory `Project` model (rasters, biomes, fluid bodies, river nodes, structure templates and instances, reference images).
  - `zipIO.ts`: converts a `Project` to and from a ZIP.
  - `fluids.ts`: solves water and lava rasters from painted fluid bodies.
  - `rivers.ts`: resolves and rasterizes river node graphs.
  - `heightMapping.ts`: mirrors `HeightMapping.java`.
  - `validate.ts`: Ajv validation that imports `docs/schemas/*.json` directly. That is why `server.fs.allow` includes `..`.
  - `png.ts`, `nbt.ts`: encoding and reading helpers.
- `src/editor/`:
  - `store.ts`: a single Zustand store. Stroke-based undo and redo patches `fluidLabels` and `biomeIndex` in place, and bumps `rev` so overlays redraw.
  - `MapView.tsx`: the canvas.
  - `brush.ts`: painting.
  - `sections/*.tsx`: the sidebar panels.
  - `worker.ts` / `workerClient.ts`: run fluid solving, ZIP import and ZIP export in a Web Worker, and transfer typed-array buffers.
  - `autosave.ts`: saves a draft ZIP to IndexedDB.
- Editor-only state goes into the exported ZIP as `editor.json` and `editor/*` files (fluid body labels PNG, body definitions, structure templates, reference images). The mod ignores these files. The editor uses them to restore a project on import. `importZip` also migrates data from older editor versions, so keep those compatibility branches.

### Keeping things in sync

The format is implemented twice, in Java (`mod/core`) and in TypeScript (`site/src/lib`). If you change the format:

1. Update `docs/FORMAT.md` and the JSON Schema in `docs/schemas/`.
2. Update the Java parser and validation in `mod/core`, and add tests.
3. Update `site/src/lib` (`zipIO.ts`, `heightMapping.ts`, …) and the editor UI.
4. If the examples should use the change, regenerate them with `mod/example/make_example.py`. The site bundles `mod/example/*.zip` into `dist/examples/`.

## CI

Workflows only run for changes in their own folder.

- `mod.yml`: runs on changes to `mod/**`. It runs `./gradlew build` and uploads the `mapgen-fabric` and `mapgen-neoforge` artifacts (the mod jar only).
- `sonar-mod.yaml`: runs SonarCloud analysis on PRs that touch `mod/`. Sonar findings get fixed regularly, so keep methods short and parameter counts low.
- `release.yml`: runs on a `v*` tag and creates a GitHub release with both jars and the example ZIPs.
- `site.yml`: runs on changes to `site/**`, `docs/**` or the example ZIPs. It runs `pnpm test` and `pnpm build`, then deploys to GitHub Pages on push to `main`.

## Conventions

- Write code comments and docs in English. The mod has `en_us` and `fr_fr` lang files, so add any new UI string to both.
- Versions are in `mod/gradle.properties` (`version`) and `site/package.json`. They are currently in lockstep at 0.2.0.
