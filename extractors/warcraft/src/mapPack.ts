import { biomeForArea, biomeId, climateOf, waterBiome, type BiomeEntry, type Climate } from './biomes.ts';
import type { GameData, MapInfo } from './gameData.ts';
import { encodePng } from './output/png.ts';
import type { StructureSet } from './structures.ts';
import { ZipWriter } from './output/zip.ts';
import { FLUID_KINDS, NO_HEIGHT, type TerrainRaster } from './terrain.ts';

const SCHEMAS = 'https://raw.githubusercontent.com/Leomaradan/minecraft-map-generator/main/docs/schemas/';
// Minecraft's world height limits.
const MIN_WORLD_Y = -2032;
const MAX_WORLD_Y = 2031;
const LAVA = FLUID_KINDS.indexOf('lava') + 1;
const OCEAN = FLUID_KINDS.indexOf('ocean') + 1;

/** normal: terrain only. floating: islands in the void (bottom.png). underground: a cave world (ceiling.png). */
export type MapType = 'normal' | 'floating' | 'underground';

export interface PackOptions {
  name: string;
  type: MapType;
  /** Yards per block, vertically. */
  verticalScale: number;
  seaLevel: number;
  /** Ground below this many yards is raised to it (deep sea beds). */
  minHeight: number;
  /** What fills places without terrain: sea down to `oceanDepth` blocks, or nothing. */
  missing: 'ocean' | 'void';
  oceanDepth: number;
  biomeOverrides: Record<string, BiomeEntry>;
  vanillaStructures: boolean;
  /** Caves and ravines (Minecraft carvers). */
  carvers: boolean;
  /** Buildings as structure templates. */
  structures?: StructureSet;
}

/** World X, Z of pixel (0, 0): the map is centered on 0, 0. */
export function mapOrigin(width: number, height: number): [number, number] {
  return [-Math.floor(width / 2), -Math.floor(height / 2)];
}

export interface PackReport {
  heightRange: [number, number];
  clamped: number;
  biomes: number;
  files: string[];
  spawn?: { x: number; y: number; z: number };
}

interface Biomes {
  /** Color per biome slot (RGB), for each area index then the water slots. */
  colors: number[];
  entries: Map<number, BiomeEntry>;
  areaClimate: Climate[];
  /** Climate of the land around each cell of a coarse grid, for the oceans. */
  oceanClimate: (x: number, y: number) => Climate;
  waterSlot: Map<string, number>;
  areaRows: { color: string; areaId: number; zone: string; name: string; biome: string }[];
}

// Floating islands: the underside is a cone, this many blocks deeper per block from the edge, up to MAX_THICKNESS.
const ISLAND_SLOPE = 0.6;
const MAX_THICKNESS = 32;
const MIN_THICKNESS = 2;

/** Heights of one column, in Minecraft Y; undefined where there is nothing. */
interface Columns {
  top: (i: number) => number | undefined;
  bottom: (i: number) => number | undefined;
  ceiling: (i: number) => number | undefined;
  fluid: (i: number) => number | undefined;
}

function columns(raster: TerrainRaster, options: PackOptions): Columns {
  const toY = (quarterYards: number) => options.seaLevel + quarterYards / 4 / options.verticalScale;
  const models = raster.hasModels ? raster.modelLayers() : undefined;
  const minGround = options.type === 'floating' ? -Infinity : options.minHeight * 4;
  const top = (i: number) => {
    let h = raster.ground[i] === NO_HEIGHT ? undefined : Math.max(raster.ground[i], minGround);
    if (options.type === 'floating' && models && models.top[i] !== NO_HEIGHT) h = Math.max(h ?? -Infinity, models.top[i]);
    return h === undefined ? undefined : toY(h);
  };

  let bottom: Columns['bottom'] = () => undefined;
  if (options.type === 'floating') {
    // Model collision meshes are often a floor only: give every island a tapered underside.
    const edge = distanceToEdge(raster.width, raster.height, (i) => top(i) !== undefined);
    bottom = (i) => {
      const t = top(i);
      if (t === undefined) return undefined;
      const taper = Math.max(MIN_THICKNESS, Math.min(MAX_THICKNESS, edge[i] * ISLAND_SLOPE));
      const modelBottom = models && models.bottom[i] !== NO_HEIGHT ? toY(models.bottom[i]) : Infinity;
      return Math.min(modelBottom, t - taper);
    };
  }

  let ceiling: Columns['ceiling'] = () => undefined;
  if (options.type === 'underground' && models) {
    // Columns without a model above them get the highest ceiling: the cave stays closed.
    let highest = -Infinity;
    for (let i = 0; i < models.ceiling.length; i++) if (models.ceiling[i] !== NO_HEIGHT) highest = Math.max(highest, models.ceiling[i]);
    if (highest > -Infinity) {
      ceiling = (i) => {
        if (raster.ground[i] === NO_HEIGHT) return undefined;
        return toY(models.ceiling[i] === NO_HEIGHT ? highest : models.ceiling[i]);
      };
    }
  }

  const fluid = (i: number) => {
    const t = top(i);
    if (t === undefined || !raster.fluidKind[i] || raster.fluid[i] === NO_HEIGHT) return undefined;
    const y = toY(raster.fluid[i]);
    return y > t ? y : undefined;
  };
  return { top, bottom, ceiling, fluid };
}

/** Distance in pixels from each pixel of a shape to its outside (chamfer 3-4 approximation). */
export function distanceToEdge(width: number, height: number, inside: (i: number) => boolean): Float32Array {
  const d = new Float32Array(width * height);
  for (let i = 0; i < d.length; i++) d[i] = inside(i) ? Infinity : 0;
  const at = (x: number, y: number) => (x < 0 || y < 0 || x >= width || y >= height ? 0 : d[y * width + x]);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = y * width + x;
      if (d[i] === 0) continue;
      d[i] = Math.min(d[i], at(x - 1, y) + 1, at(x, y - 1) + 1, at(x - 1, y - 1) + Math.SQRT2, at(x + 1, y - 1) + Math.SQRT2);
    }
  }
  for (let y = height - 1; y >= 0; y--) {
    for (let x = width - 1; x >= 0; x--) {
      const i = y * width + x;
      if (d[i] === 0) continue;
      d[i] = Math.min(d[i], at(x + 1, y) + 1, at(x, y + 1) + 1, at(x + 1, y + 1) + Math.SQRT2, at(x - 1, y + 1) + Math.SQRT2);
    }
  }
  return d;
}

/** Writes the map ZIP for the Minecraft Map Generator mod. */
export async function writeMapPack(
  path: string,
  raster: TerrainRaster,
  game: GameData,
  map: MapInfo,
  options: PackOptions,
  metadata: Record<string, unknown>,
): Promise<PackReport> {
  const { width, height } = raster;
  const size = width * height;
  const column = columns(raster, options);
  // Islands and caves float in the void: an open sea around them would make no sense.
  const ocean = options.missing === 'ocean' && options.type === 'normal';
  const oceanFloorY = options.seaLevel - options.oceanDepth;

  // Height range of everything that will be written.
  let minY = Infinity;
  let maxY = -Infinity;
  let hasMissing = false;
  let hasBottom = false;
  let hasCeiling = false;
  let hasWater = false;
  let hasLava = false;
  for (let i = 0; i < size; i++) {
    const top = column.top(i);
    if (top === undefined) {
      hasMissing = true;
      continue;
    }
    minY = Math.min(minY, top);
    maxY = Math.max(maxY, top);
    const bottom = column.bottom(i);
    if (bottom !== undefined) {
      hasBottom = true;
      minY = Math.min(minY, bottom);
    }
    const ceiling = column.ceiling(i);
    if (ceiling !== undefined) {
      hasCeiling = true;
      maxY = Math.max(maxY, ceiling);
    }
    const fluid = column.fluid(i);
    if (fluid !== undefined) {
      maxY = Math.max(maxY, fluid);
      if (raster.fluidKind[i] === LAVA) hasLava = true;
      else hasWater = true;
    }
  }
  if (minY === Infinity) throw new Error('no terrain in the selected area');
  if (hasMissing && ocean) {
    minY = Math.min(minY, oceanFloorY);
    maxY = Math.max(maxY, options.seaLevel);
    hasWater = true;
  }
  const clampedMin = Math.max(MIN_WORLD_Y, Math.floor(minY) - 1);
  const clampedMax = Math.min(MAX_WORLD_Y, Math.ceil(maxY));
  // Value 0 is reserved (no fluid, void), so the lowest ground is 1 block above base_y.
  const baseY = clampedMin;
  let step = 1 / 16;
  while ((clampedMax - baseY) / step > 65535) step *= 2;
  let clamped = 0;
  const encode = (y: number) => {
    if (y < MIN_WORLD_Y || y > MAX_WORLD_Y) clamped++;
    return Math.max(1, Math.min(65535, Math.round((Math.min(MAX_WORLD_Y, Math.max(MIN_WORLD_Y, y)) - baseY) / step)));
  };
  const layer = (value: (i: number) => number | undefined) => async () =>
    encodePng(width, height, 'gray16', (y, row) => {
      for (let x = 0; x < width; x++) {
        const v = value(y * width + x);
        row.writeUInt16BE(v === undefined ? 0 : encode(v), x * 2);
      }
    });

  const biomes = buildBiomes(raster, game, options);
  const zip = new ZipWriter(path);
  const files: string[] = [];
  const add = (name: string, data: Buffer) => {
    zip.add(name, data);
    files.push(name);
  };

  add('heightmap.png', await layer((i) => column.top(i) ?? (ocean ? oceanFloorY : undefined))());
  if (hasWater) {
    add('water.png', await layer((i) => {
      if (column.top(i) === undefined) return ocean ? options.seaLevel : undefined;
      return raster.fluidKind[i] === LAVA ? undefined : column.fluid(i);
    })());
  }
  if (hasLava) add('lava.png', await layer((i) => (raster.fluidKind[i] === LAVA ? column.fluid(i) : undefined))());
  if (hasBottom) add('bottom.png', await layer(column.bottom)());
  if (hasCeiling) add('ceiling.png', await layer(column.ceiling)());

  add('biomes.png', await encodePng(width, height, 'rgb8', (y, row) => {
    for (let x = 0; x < width; x++) {
      const color = biomes.colors[biomeSlot(raster, x, y, column.top(y * width + x) !== undefined, biomes, options)];
      row[x * 3] = color >> 16;
      row[x * 3 + 1] = (color >> 8) & 0xff;
      row[x * 3 + 2] = color & 0xff;
    }
  }));

  const biomesJson: Record<string, unknown> = { $schema: `${SCHEMAS}biomes.schema.json` };
  for (const [slot, entry] of biomes.entries) biomesJson[hex(biomes.colors[slot])] = entry;
  add('biomes.json', json(biomesJson));

  // Pixel (0, 0) at origin, so spawn and structure coordinates are easy to compute.
  const origin = mapOrigin(width, height);
  if (options.structures && options.structures.placements.length > 0) {
    for (const file of options.structures.files) add(file.path, file.data);
    add('structures.json', json({
      $schema: `${SCHEMAS}structures.schema.json`,
      coordinates: 'world',
      structures: options.structures.placements.map((p) => ({ type: 'template', path: p.path, x: p.x, y: p.y, z: p.z })),
    }));
  }
  const spawnPixel = options.type === 'normal' ? undefined : findSpawn(raster, column);
  const spawn = spawnPixel && {
    x: origin[0] + spawnPixel.x,
    y: Math.min(MAX_WORLD_Y, Math.floor(spawnPixel.y) + 1),
    z: origin[1] + spawnPixel.z,
  };

  const mapJson = {
    $schema: `${SCHEMAS}map.schema.json`,
    format: 1,
    name: options.name,
    origin,
    height: { base_y: baseY, scale: step },
    sea_level: options.seaLevel,
    biome_transition: 8,
    outside: ocean
      ? { mode: 'flat', ground_level: Math.round(oceanFloorY), water_level: options.seaLevel, biome: 'minecraft:ocean', border: true }
      : { mode: 'void', biome: 'minecraft:the_void', border: true },
    ...(spawn ? { spawn } : {}),
    blocks: { bedrock: ocean },
    decoration: { features: { disabled_steps: ['lakes', 'fluid_springs'] }, carvers: options.carvers },
    vanilla_structures: options.vanillaStructures,
  };
  add('map.json', json(mapJson));

  add('extractor.json', json({ ...metadata, map: { id: map.id, directory: map.directory, name: map.name }, areas: biomes.areaRows }));
  zip.close();

  // Don't list every template in the report.
  const listed = files.filter((f) => !f.startsWith('structures/'));
  const templates = files.length - listed.length;
  if (templates > 0) listed.push(`structures/ (${templates} templates)`);
  return { heightRange: [Math.round(minY), Math.round(maxY)], clamped, biomes: biomes.entries.size, files: listed, spawn };
}

/**
 * A dry spot with head room, as close as possible to the center: vanilla would
 * spawn players in the void around floating islands or on top of a ceiling.
 */
function findSpawn(raster: TerrainRaster, column: Columns): { x: number; y: number; z: number } | undefined {
  const cx = raster.width / 2;
  const cy = raster.height / 2;
  let best: { x: number; y: number; z: number } | undefined;
  let bestDistance = Infinity;
  for (let y = 0; y < raster.height; y++) {
    for (let x = 0; x < raster.width; x++) {
      const distance = (x - cx) ** 2 + (y - cy) ** 2;
      if (distance >= bestDistance) continue;
      const i = y * raster.width + x;
      const top = column.top(i);
      if (top === undefined || column.fluid(i) !== undefined) continue;
      const ceiling = column.ceiling(i);
      if (ceiling !== undefined && ceiling - top < 4) continue;
      best = { x, y: top, z: y };
      bestDistance = distance;
    }
  }
  return best;
}

function buildBiomes(raster: TerrainRaster, game: GameData, options: PackOptions): Biomes {
  if (raster.areaIds.length === 0) raster.areaIndexOf(0); // no chunk at all: one default slot
  const used = new Set<string>();
  const colors: number[] = [];
  const entries = new Map<number, BiomeEntry>();
  const areaClimate: Climate[] = [];
  const areaRows: Biomes['areaRows'] = [];
  const unique = (seed: number) => {
    let color = seed & 0xffffff;
    while (used.has(hex(color))) color = (color * 1103515245 + 12345) & 0xffffff;
    used.add(hex(color));
    return color;
  };

  raster.areaIds.forEach((areaId, index) => {
    const chain = game.areaChain(areaId);
    const entry = biomeForArea(chain, options.biomeOverrides);
    const color = unique(areaColor(areaId));
    colors[index] = color;
    entries.set(index, entry);
    areaClimate[index] = climateOf(entry);
    areaRows.push({ color: hex(color), areaId, zone: chain.at(-1)?.zoneName ?? '', name: chain[0]?.name ?? '', biome: biomeId(entry) });
  });

  // Water slots are created on demand by biomeSlot().
  return { colors, entries, areaClimate, oceanClimate: landClimateGrid(raster, areaClimate), waterSlot: new Map(), areaRows };
}

const CLIMATE_CELL = 64;
const CLIMATE_RADIUS = 4;

/**
 * Majority climate of the dry land near each 64-pixel cell. Ocean chunks often
 * carry a meaningless area ID, so the sea takes the climate of its coasts.
 */
function landClimateGrid(raster: TerrainRaster, areaClimate: Climate[]): (x: number, y: number) => Climate {
  const climates: Climate[] = ['frozen', 'warm', 'temperate'];
  const cols = Math.ceil(raster.width / CLIMATE_CELL);
  const rows = Math.ceil(raster.height / CLIMATE_CELL);
  const counts = new Float64Array(cols * rows * 3);
  for (let y = 0; y < raster.height; y++) {
    for (let x = 0; x < raster.width; x++) {
      const i = y * raster.width + x;
      if (raster.ground[i] === NO_HEIGHT || (raster.fluidKind[i] && raster.fluid[i] > raster.ground[i])) continue;
      const cell = Math.floor(y / CLIMATE_CELL) * cols + Math.floor(x / CLIMATE_CELL);
      counts[cell * 3 + climates.indexOf(areaClimate[raster.area[i]])]++;
    }
  }
  const grid: Climate[] = new Array(cols * rows);
  for (let cy = 0; cy < rows; cy++) {
    for (let cx = 0; cx < cols; cx++) {
      const total = [0, 0, 0];
      for (let ny = Math.max(0, cy - CLIMATE_RADIUS); ny <= Math.min(rows - 1, cy + CLIMATE_RADIUS); ny++) {
        for (let nx = Math.max(0, cx - CLIMATE_RADIUS); nx <= Math.min(cols - 1, cx + CLIMATE_RADIUS); nx++) {
          for (let c = 0; c < 3; c++) total[c] += counts[(ny * cols + nx) * 3 + c];
        }
      }
      // Frozen or warm seas need a clear majority of such coasts.
      const sum = total[0] + total[1] + total[2];
      const best = total.indexOf(Math.max(...total));
      grid[cy * cols + cx] = sum > 0 && total[best] > sum * 0.6 ? climates[best] : 'temperate';
    }
  }
  return (x, y) => grid[Math.floor(y / CLIMATE_CELL) * cols + Math.floor(x / CLIMATE_CELL)];
}

function biomeSlot(raster: TerrainRaster, x: number, y: number, hasGround: boolean, biomes: Biomes, options: PackOptions): number {
  const i = y * raster.width + x;
  let water: string | undefined;
  if (!hasGround) {
    water = waterBiome('ocean', biomes.oceanClimate(x, y), true);
  } else {
    const kind = raster.fluidKind[i];
    const depth = (raster.fluid[i] - Math.max(raster.ground[i], options.minHeight * 4)) / 4 / options.verticalScale;
    if (kind && kind !== LAVA && depth >= 2) {
      const climate = kind === OCEAN ? biomes.oceanClimate(x, y) : biomes.areaClimate[raster.area[i]];
      water = waterBiome(kind === OCEAN ? 'ocean' : 'river', climate, depth > 25);
    }
  }
  if (!water) return raster.area[i];
  let slot = biomes.waterSlot.get(water);
  if (slot === undefined) {
    slot = biomes.colors.length;
    const used = new Set(biomes.colors.map(hex));
    let color = waterColor(water);
    while (used.has(hex(color))) color = (color + 1) & 0xffffff;
    biomes.colors.push(color);
    biomes.entries.set(slot, water);
    biomes.waterSlot.set(water, slot);
  }
  return slot;
}

/** A stable, fairly saturated color per area ID. */
function areaColor(areaId: number): number {
  let h = Math.imul(areaId ^ 0x9e3779b9, 0x85ebca6b);
  h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35) ^ (h >>> 16);
  const hue = (h >>> 0) % 360;
  const [r, g, b] = hsl(hue, 0.55, 0.45 + ((h >>> 9) % 20) / 100);
  return (r << 16) | (g << 8) | b;
}

function waterColor(biome: string): number {
  const known: Record<string, number> = {
    'minecraft:ocean': 0x1e3c8c, 'minecraft:deep_ocean': 0x10245a, 'minecraft:warm_ocean': 0x2a7fbf,
    'minecraft:deep_lukewarm_ocean': 0x1b5a8c, 'minecraft:frozen_ocean': 0x8fa8d8, 'minecraft:deep_frozen_ocean': 0x5a73a8,
    'minecraft:river': 0x3f76e4, 'minecraft:frozen_river': 0xa0b8f0,
  };
  return known[biome] ?? 0x2040a0;
}

function hsl(h: number, s: number, l: number): [number, number, number] {
  const k = (n: number) => (n + h / 30) % 12;
  const a = s * Math.min(l, 1 - l);
  const f = (n: number) => Math.round(255 * (l - a * Math.max(-1, Math.min(k(n) - 3, 9 - k(n), 1))));
  return [f(0), f(8), f(4)];
}

function hex(color: number): string {
  return `#${color.toString(16).padStart(6, '0').toUpperCase()}`;
}

function json(value: unknown): Buffer {
  return Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
}
