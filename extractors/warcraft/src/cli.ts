#!/usr/bin/env node
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { parseArgs } from 'node:util';
import { CascStorage } from './casc/storage.ts';
import { extractMap, mapTiles, rasterSize } from './extract.ts';
import { GameData, type MapInfo } from './gameData.ts';
import { tileBounds } from './terrain.ts';

const USAGE = `Usage: wow-map-extractor <command> [options]

Commands:
  list [filter]            Maps with terrain (ID, directory, tiles, name); asks for filters in a terminal
  zones <map>              Zones of a map (ID, internal name, name)
  extract <map>            Writes a map ZIP for the Minecraft Map Generator mod

<map> is a map ID (0, 2552), directory (Azeroth) or name. IDs come first: some maps only have one.

Common options:
  --wow <dir>              World of Warcraft folder (default: $WOW_DIR, or auto-detected)
  --product <name>         Product in .build.info: wow, wow_classic, ... (default: wow)

Extract options:
  -o, --output <file>      Output ZIP (default: <name> or <map>[-<zone>], .zip)
  --name <name>            World name, also used for the ZIP name (default: the map's name)
  --type <type>            auto, normal, floating (islands: bottom.png) or underground (caves: ceiling.png)
                           (default: auto, which detects maps like SkywallRaid and Deephome)
  --zone <area>            Only this zone (ID, internal name or name; repeatable)
  --margin <yards>         Extra terrain around --zone (default: 64)
  --tiles <x0,y0,x1,y1>    Only this range of ADT tiles (inclusive)
  --scale <yards>          Yards per block horizontally (default: 2; 1 is real scale)
  --vertical-scale <yards> Yards per block vertically (default: same as --scale)
  --sea-level <y>          Minecraft Y of WoW's sea level (default: 63)
  --min-height <yards>     Raise deeper sea beds to this height (default: -256)
  --missing <ocean|void>   Fill places without terrain (default: ocean for open worlds, void for instances)
  --ocean-depth <blocks>   Depth of that ocean (default: 32)
  --biomes <file.json>     Biome overrides: {"<zone name or area ID>": "minecraft:<biome>" | {biomes.json entry}}
  --vanilla-structures     Let Minecraft generate villages, temples...
  --carvers                Let Minecraft dig caves and ravines
  --structures             Also convert the buildings (WMO) into structure templates (.nbt) placed by the map
  --force                  Skip the size checks: very large images, heavy structures (no question asked)
`;

async function main(): Promise<void> {
  const { positionals, values } = parseArgs({
    allowPositionals: true,
    options: {
      wow: { type: 'string' },
      product: { type: 'string', default: 'wow' },
      output: { type: 'string', short: 'o' },
      zone: { type: 'string', multiple: true },
      margin: { type: 'string', default: '64' },
      tiles: { type: 'string' },
      scale: { type: 'string', default: '2' },
      'vertical-scale': { type: 'string' },
      'sea-level': { type: 'string', default: '63' },
      'min-height': { type: 'string', default: '-256' },
      missing: { type: 'string' },
      'ocean-depth': { type: 'string', default: '32' },
      biomes: { type: 'string' },
      'vanilla-structures': { type: 'boolean', default: false },
      carvers: { type: 'boolean', default: false },
      structures: { type: 'boolean', default: false },
      type: { type: 'string', default: 'auto' },
      name: { type: 'string' },
      force: { type: 'boolean', default: false },
      help: { type: 'boolean', short: 'h' },
    },
  });
  const [command, query] = positionals;
  if (values.help || !command) {
    process.stdout.write(USAGE);
    return;
  }

  const wowDir = values.wow ?? process.env.WOW_DIR ?? detectWowDir();
  if (!wowDir) throw new Error('World of Warcraft folder not found: pass --wow <dir> or set WOW_DIR');
  const storage = new CascStorage(wowDir, values.product);
  log(`World of Warcraft ${storage.build.version} (${storage.locale}) in ${wowDir}`);
  const game = await GameData.load(storage);

  switch (command) {
    case 'list': {
      const rows = [...game.maps.values()]
        .sort((a, b) => a.id - b.id)
        .map((map) => ({ map, tiles: safeTiles(() => mapTiles(storage, map).length) }))
        .filter((r) => r.tiles > 0);
      printMaps(rows, query);
      if (process.stdin.isTTY && process.stdout.isTTY) await filterLoop(rows);
      break;
    }
    case 'zones': {
      const map = requireMap(game, query);
      const zones = [...game.areas.values()].filter((a) => a.mapId === map.id && a.parentId === 0);
      for (const zone of zones) console.log(`${String(zone.id).padStart(6)}  ${zone.zoneName.padEnd(36)} ${zone.name}`);
      break;
    }
    case 'extract': {
      const map = requireMap(game, query);
      const scale = positive(values.scale, '--scale');
      const tilesOption = values.tiles ? parseTiles(values.tiles) : undefined;
      if (!values.zone && !values.force) {
        const tiles = mapTiles(storage, map).filter((t) => !tilesOption || (t.x >= tilesOption[0] && t.x <= tilesOption[2] && t.y >= tilesOption[1] && t.y <= tilesOption[3]));
        if (tiles.length > 0) {
          const { width, height } = rasterSize(tileBounds(tiles), scale);
          if (width * height > 100_000_000) {
            throw new Error(`the map would be ${width} x ${height} pixels: use --zone, --tiles or a larger --scale (or --force)`);
          }
        }
      }
      const slug = (values.name ?? [map.directory, ...(values.zone ?? [])].join('-')).replace(/[^\p{L}\p{N}_-]+/gu, '_');
      const output = resolve(values.output ?? `${slug}.zip`);
      const started = Date.now();
      const result = await extractMap(storage, game, map, output, {
        name: values.name,
        scale,
        verticalScale: values['vertical-scale'] ? positive(values['vertical-scale'], '--vertical-scale') : scale,
        seaLevel: Number(values['sea-level']),
        minHeight: Number(values['min-height']),
        missing: (values.missing ?? (map.instanceType === 0 ? 'ocean' : 'void')) as 'ocean' | 'void',
        oceanDepth: Number(values['ocean-depth']),
        biomeOverrides: values.biomes ? JSON.parse(readFileSync(values.biomes, 'utf8')) : {},
        vanillaStructures: values['vanilla-structures'],
        carvers: values.carvers,
        structures: values.structures,
        confirmHeavy: (warnings) => confirmHeavy(warnings, values.force),
        type: mapType(values.type),
        tiles: tilesOption,
        zones: values.zone,
        margin: Number(values.margin),
        log,
      });
      const { pack } = result;
      log(`Wrote ${output} in ${((Date.now() - started) / 1000).toFixed(1)} s`);
      if (result.type !== 'normal') log(`  ${result.type} map${pack.spawn ? `, spawn at ${pack.spawn.x} ${pack.spawn.y} ${pack.spawn.z}` : ''}`);
      log(`  ${result.width} x ${result.height} pixels, Y ${pack.heightRange[0]} to ${pack.heightRange[1]}, ${pack.biomes} biome colors`);
      log(`  files: ${pack.files.join(', ')}`);
      if (result.structures) log(`  structures: ${result.structures.placements.length} template placements`);
      if (pack.clamped > 0) log(`  warning: ${pack.clamped} values were outside Minecraft's height limits and were clamped`);
      if (result.width > 8192 || result.height > 8192) {
        log('  warning: images this large need a lot of memory in Minecraft; consider --zone or a larger --scale');
      }
      break;
    }
    default:
      throw new Error(`unknown command '${command}'\n\n${USAGE}`);
  }
  storage.close();
}

function requireMap(game: GameData, query: string | undefined) {
  if (!query) throw new Error('missing <map> argument');
  const map = game.findMap(query);
  if (!map) throw new Error(`unknown map '${query}' (see the list command)`);
  return map;
}

type MapRow = { map: MapInfo; tiles: number };

function printMaps(rows: MapRow[], filter?: string): void {
  const matches = filter ? rows.filter(({ map }) => matchesFilter(map, filter)) : rows;
  for (const { map, tiles } of matches) {
    console.log(`${String(map.id).padStart(5)}  ${map.directory.padEnd(32)} ${String(tiles).padStart(5)} tiles  ${map.name}`);
  }
  console.log(`${matches.length} map(s)${filter ? ` matching "${filter}"` : ''}`);
}

/** An ID matches exactly; other words match the directory or the name. */
function matchesFilter(map: MapInfo, filter: string): boolean {
  return filter
    .toLowerCase()
    .split(/\s+/)
    .filter((w) => w !== '')
    .every((word) => String(map.id) === word || map.directory.toLowerCase().includes(word) || map.name.toLowerCase().includes(word));
}

/** Keeps the list open and asks for filters until an empty answer. */
async function filterLoop(rows: MapRow[]): Promise<void> {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  try {
    for (;;) {
      const answer = (await rl.question('\nFilter the maps (ID, directory or name words; Enter to quit): ')).trim();
      if (answer === '' || answer.toLowerCase() === 'q') break;
      printMaps(rows, answer);
    }
  } finally {
    rl.close();
  }
}

/**
 * Warns about heavy structures. --force writes them without asking; in a terminal the
 * user decides; otherwise the extraction stops.
 */
async function confirmHeavy(warnings: string[], force: boolean): Promise<boolean> {
  log('Warning: the structures are heavy for Minecraft:');
  for (const warning of warnings) log(`  - ${warning}`);
  log('  World creation and generation will be slow and use a lot of memory. A larger --scale or a --zone makes them lighter.');
  if (force) {
    log('  --force: writing them anyway.');
    return true;
  }
  if (!process.stdin.isTTY || !process.stderr.isTTY) {
    log('  Pass --force to write them anyway.');
    return false;
  }
  const rl = createInterface({ input: process.stdin, output: process.stderr });
  try {
    const answer = (await rl.question('  Write them anyway? [y/N] ')).trim().toLowerCase();
    return answer === 'y' || answer === 'yes' || answer === 'o' || answer === 'oui';
  } finally {
    rl.close();
  }
}

function mapType(value: string | undefined): 'auto' | 'normal' | 'floating' | 'underground' {
  if (value === 'auto' || value === 'normal' || value === 'floating' || value === 'underground') return value;
  throw new Error('--type must be auto, normal, floating or underground');
}

function safeTiles(count: () => number): number {
  try {
    return count();
  } catch {
    return 0; // encrypted or missing WDT
  }
}

function positive(value: string | undefined, name: string): number {
  const n = Number(value);
  if (!(n > 0)) throw new Error(`${name} must be a positive number`);
  return n;
}

function parseTiles(value: string): [number, number, number, number] {
  const parts = value.split(',').map(Number);
  if (parts.length !== 4 || parts.some((n) => !Number.isInteger(n) || n < 0 || n > 63)) {
    throw new Error('--tiles must be x0,y0,x1,y1 with tile numbers from 0 to 63');
  }
  const [x0, y0, x1, y1] = parts;
  return [Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)];
}

/** Looks for WoW in the usual places, including Steam (Proton) and Lutris prefixes. */
function detectWowDir(): string | undefined {
  const home = homedir();
  const programFiles = ['Program Files (x86)', 'Program Files'];
  const prefixes = [join(home, '.wine'), join(home, 'Games', 'battlenet'), join(home, 'Games', 'world-of-warcraft')];
  const compat = join(home, '.local', 'share', 'Steam', 'steamapps', 'compatdata');
  if (existsSync(compat)) {
    for (const id of readdirSync(compat)) prefixes.push(join(compat, id, 'pfx'));
  }
  const candidates = [
    ...prefixes.flatMap((p) => programFiles.map((pf) => join(p, 'drive_c', pf, 'World of Warcraft'))),
    'C:\\Program Files (x86)\\World of Warcraft',
    '/Applications/World of Warcraft',
  ];
  return candidates.find((dir) => existsSync(join(dir, '.build.info')));
}

function log(message: string): void {
  process.stderr.write(`${message}\n`);
}

main().catch((error: unknown) => {
  process.stderr.write(`Error: ${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
