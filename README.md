# Minecraft Map Generator

Tools for building Minecraft worlds from predefined maps: a heightmap, a biome map, water and lava
maps, a world configuration and a structure list, packed in a ZIP file.

## Repository layout

| Folder | Content |
|---|---|
| [`mod/`](mod/) | The Minecraft 26.3 mod (Fabric and NeoForge) that generates a world from a map ZIP. See [mod/README.md](mod/README.md). |
| [`docs/`](docs/) | Shared documentation, including the [map ZIP format](docs/FORMAT.md). |

Other folders (website, map templates, extraction tools...) will sit next to `mod/`, each with its
own build. CI workflows are scoped by folder: the mod is only built when something under `mod/`
changes.
