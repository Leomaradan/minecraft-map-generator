import type { CascStorage } from './casc/storage.ts';
import { CHUNK_SIZE, TILE_SIZE } from './formats/adt.ts';
import { readWdt, type WdtTile } from './formats/wdt.ts';
import type { GameData, MapInfo } from './gameData.ts';
import type { WmoPlacement } from './formats/wmo.ts';
import { mapOrigin, writeMapPack, type MapType, type PackOptions, type PackReport } from './mapPack.ts';
import { buildStructures, type StructureSet } from './structures.ts';
import { collectPlacements, rasterizeModels, type ModelReport } from './models.ts';
import { NO_HEIGHT, TerrainRaster, loadAdt, renderTerrain, tileBounds, type Bounds, type RenderReport } from './terrain.ts';

export interface ExtractOptions extends Omit<PackOptions, 'name' | 'type' | 'structures'> {
  name?: string;
  /** Convert the buildings (WMO) into structure templates. */
  structures: boolean;
  /**
   * Called when the structures are heavy for Minecraft, with the reasons. Returns
   * true to write them anyway; the extraction stops otherwise.
   */
  confirmHeavy: (warnings: string[]) => Promise<boolean>;
  /** Kind of map; `auto` detects floating islands and underground maps. */
  type: MapType | 'auto';
  /** Yards per block, horizontally. */
  scale: number;
  /** Inclusive tile range [x0, y0, x1, y1]. */
  tiles?: [number, number, number, number];
  /** Area IDs or names: crop to the chunks of these areas (and their sub-areas). */
  zones?: string[];
  /** Extra yards around a zone. */
  margin: number;
  log: (message: string) => void;
}

export interface ExtractResult {
  type: MapType;
  width: number;
  height: number;
  render: RenderReport;
  models?: ModelReport;
  structures?: StructureSet;
  pack: PackReport;
}

/** Above these, the structures are slow to load and generate in Minecraft. */
export const HEAVY_STRUCTURES = { blocks: 2_000_000, templates: 5_000, megabytes: 100, buildingBlocks: 500_000 };

// Space kept between the ground and a ceiling, in yards: lower model surfaces are furniture, not the roof.
const CEILING_CLEARANCE = 3;
// A WMO this large (footprint in square yards) can be the roof of a cave.
const LARGE_WMO_AREA = (TILE_SIZE * TILE_SIZE) / 4;
// Yards per pixel of the coarse rendering used to detect underground maps.
const DETECTION_SCALE = 16;

export function mapTiles(storage: CascStorage, map: MapInfo): WdtTile[] {
  const wdt = map.wdtFileDataId ? storage.readFile(map.wdtFileDataId) : undefined;
  return wdt ? readWdt(wdt) : [];
}

/** Predicted raster size for a selection, to warn before a huge extraction. */
export function rasterSize(bounds: Bounds, scale: number): { width: number; height: number } {
  return { width: Math.round((bounds.x1 - bounds.x0) / scale), height: Math.round((bounds.y1 - bounds.y0) / scale) };
}

export async function extractMap(
  storage: CascStorage,
  game: GameData,
  map: MapInfo,
  output: string,
  options: ExtractOptions,
): Promise<ExtractResult> {
  let tiles = mapTiles(storage, map);
  if (tiles.length === 0) throw new Error(`map ${map.directory} has no terrain tiles (WMO-only map?)`);

  if (options.tiles) {
    const [x0, y0, x1, y1] = options.tiles;
    tiles = tiles.filter((t) => t.x >= Math.min(x0, x1) && t.x <= Math.max(x0, x1) && t.y >= Math.min(y0, y1) && t.y <= Math.max(y0, y1));
    if (tiles.length === 0) throw new Error('no terrain tile in the selected tile range');
  }

  let bounds = tileBounds(tiles);
  const zoneNames: string[] = [];
  if (options.zones?.length) {
    const areaIds = new Set<number>();
    for (const query of options.zones) {
      const found = game.findAreas(query, map.id);
      if (found.length === 0) throw new Error(`unknown area '${query}' on map ${map.directory}`);
      found.forEach((a) => areaIds.add(a.id));
      zoneNames.push(found[0].name || found[0].zoneName);
    }
    options.log(`Locating ${options.zones.join(', ')}...`);
    const zoneBounds = findAreaBounds(storage, game, tiles, areaIds);
    if (!zoneBounds) throw new Error(`no terrain of ${options.zones.join(', ')} on map ${map.directory}`);
    bounds = {
      x0: Math.max(bounds.x0, zoneBounds.x0 - options.margin),
      y0: Math.max(bounds.y0, zoneBounds.y0 - options.margin),
      x1: Math.min(bounds.x1, zoneBounds.x1 + options.margin),
      y1: Math.min(bounds.y1, zoneBounds.y1 + options.margin),
    };
  }

  const allPlacements = collectPlacements(storage, tiles);
  if (options.type === 'auto') options.log('Detecting the map type...');
  const type = options.type === 'auto' ? detectType(storage, game, tiles, allPlacements) : options.type;
  if (type !== 'normal') options.log(`Map type: ${type}${options.type === 'auto' ? ' (detected)' : ''}`);
  if (type === 'floating') {
    // The islands are models: keep the area around them only.
    const placed = allPlacements.filter((p) => overlapsBounds(p.extents, bounds));
    if (placed.length > 0) {
      bounds = {
        x0: Math.max(bounds.x0, Math.min(...placed.map((p) => p.extents[0])) - options.margin),
        y0: Math.max(bounds.y0, Math.min(...placed.map((p) => p.extents[2])) - options.margin),
        x1: Math.min(bounds.x1, Math.max(...placed.map((p) => p.extents[3])) + options.margin),
        y1: Math.min(bounds.y1, Math.max(...placed.map((p) => p.extents[5])) + options.margin),
      };
    }
  }

  const raster = new TerrainRaster(bounds, options.scale);
  options.log(`Sampling ${raster.width} x ${raster.height} pixels (${options.scale} yards per block)...`);
  const render = renderTerrain(raster, tiles, storage, game, progress(options.log, 'tiles'), type === 'floating');
  for (const skipped of render.tilesSkipped) options.log(`  skipped tile ${skipped.x}_${skipped.y}: ${skipped.reason}`);

  let models: ModelReport | undefined;
  if (type !== 'normal') {
    options.log(`Rasterizing models (WMO)...`);
    models = rasterizeModels(raster, allPlacements, storage, { ceiling: type === 'underground', clearance: CEILING_CLEARANCE }, progress(options.log, 'models'));
    options.log(`  ${models.placements} models, ${models.triangles} triangles${models.skipped ? `, ${models.skipped} unreadable` : ''}`);
  }

  let structures: StructureSet | undefined;
  if (options.structures) {
    if (type === 'floating') {
      options.log('--structures ignored: on a floating map, the models already are the islands');
    } else {
      // On underground maps, the large models are the cave itself (the ceiling).
      const buildings = type === 'underground' ? allPlacements.filter((p) => footprint(p) < LARGE_WMO_AREA) : allPlacements;
      options.log('Converting buildings (WMO) into structures...');
      structures = buildStructures(raster, buildings, storage, { ...options, origin: mapOrigin(raster.width, raster.height) }, progress(options.log, 'buildings'));
      const megabytes = structures.bytes / 1024 / 1024;
      options.log(
        `  ${structures.buildings} buildings, ${structures.blocks.toLocaleString('en')} blocks, ${structures.files.length} templates (${megabytes.toFixed(1)} MB)` +
          (structures.skipped ? `, ${structures.skipped} unreadable` : ''),
      );
      const warnings = heavyWarnings(structures);
      if (warnings.length > 0 && !(await options.confirmHeavy(warnings))) {
        throw new Error('extraction cancelled: the structures are too heavy (use --force to write them anyway)');
      }
    }
  }

  options.log('Writing map ZIP...');
  const name = options.name ?? (zoneNames.length ? `${zoneNames.join(', ')} (${map.name})` : map.name);
  const pack = await writeMapPack(output, raster, game, map, { ...options, name, type, structures }, {
    generator: 'warcraft-map-extractor',
    wowVersion: storage.build.version,
    type,
    yardsPerBlock: options.scale,
    verticalYardsPerBlock: options.verticalScale,
    boundsYards: bounds,
    tileRange: [
      Math.floor(bounds.x0 / TILE_SIZE),
      Math.floor(bounds.y0 / TILE_SIZE),
      Math.ceil(bounds.x1 / TILE_SIZE) - 1,
      Math.ceil(bounds.y1 / TILE_SIZE) - 1,
    ],
  });
  return { type, width: raster.width, height: raster.height, render, models, structures, pack };
}

function footprint(p: WmoPlacement): number {
  return (p.extents[3] - p.extents[0]) * (p.extents[5] - p.extents[2]);
}

/** Why a set of structures would be heavy for Minecraft, if it is. */
export function heavyWarnings(set: StructureSet): string[] {
  const limits = HEAVY_STRUCTURES;
  const warnings: string[] = [];
  if (set.blocks > limits.blocks) warnings.push(`${set.blocks.toLocaleString('en')} blocks (more than ${limits.blocks.toLocaleString('en')})`);
  if (set.files.length > limits.templates) warnings.push(`${set.files.length} template files (more than ${limits.templates})`);
  const megabytes = set.bytes / 1024 / 1024;
  if (megabytes > limits.megabytes) warnings.push(`${megabytes.toFixed(0)} MB of templates (more than ${limits.megabytes} MB)`);
  if (set.largest && set.largest.blocks > limits.buildingBlocks) {
    warnings.push(`one building (WMO ${set.largest.fileDataId}) has ${set.largest.blocks.toLocaleString('en')} blocks (more than ${limits.buildingBlocks.toLocaleString('en')})`);
  }
  return warnings;
}

/**
 * Floating: the terrain is (almost) all holes but models exist, like the Throne of
 * the Four Winds. Underground: models cover a large share of the terrain from
 * above, like Deephome.
 */
export function detectType(storage: CascStorage, game: GameData, tiles: WdtTile[], placements: WmoPlacement[]): MapType {
  if (placements.length === 0) return 'normal';
  // Sample up to 16 tiles for holes.
  const step = Math.max(1, Math.floor(tiles.length / 16));
  let chunks = 0;
  let holes = 0;
  for (let t = 0; t < tiles.length; t += step) {
    const adt = loadAdt(storage, tiles[t]);
    if (typeof adt === 'string') continue;
    for (const chunk of adt.chunks) {
      if (!chunk) continue;
      chunks++;
      if (chunk.holes[0] === 0xffffffff && chunk.holes[1] === 0xffffffff) holes++;
    }
  }
  if (chunks > 0 && holes / chunks >= 0.95) return 'floating';

  // Cheap test first: large models over most tiles.
  const large = placements.filter((p) => footprint(p) >= LARGE_WMO_AREA);
  const covered = tiles.filter((tile) => {
    const x = (tile.x + 0.5) * TILE_SIZE;
    const y = (tile.y + 0.5) * TILE_SIZE;
    return large.some((p) => x >= p.extents[0] && x <= p.extents[3] && y >= p.extents[2] && y <= p.extents[5]);
  });
  if (covered.length / tiles.length < 0.25) return 'normal';

  // Then a coarse ceiling: a cave roofs a large share of the terrain, and a large area
  // (a tower or a ship covers a large share of a small map, a continent a large area).
  const raster = new TerrainRaster(tileBounds(tiles), DETECTION_SCALE);
  renderTerrain(raster, tiles, storage, game);
  rasterizeModels(raster, placements, storage, { ceiling: true, clearance: CEILING_CLEARANCE });
  const ceiling = raster.modelLayers().ceiling;
  let terrain = 0;
  let roofed = 0;
  for (let i = 0; i < ceiling.length; i++) {
    if (raster.ground[i] === NO_HEIGHT) continue;
    terrain++;
    if (ceiling[i] !== NO_HEIGHT) roofed++;
  }
  const roofedTiles = (roofed * DETECTION_SCALE * DETECTION_SCALE) / (TILE_SIZE * TILE_SIZE);
  return terrain > 0 && roofed / terrain >= 0.15 && roofedTiles >= 5 ? 'underground' : 'normal';
}

function overlapsBounds(extents: WmoPlacement['extents'], bounds: Bounds): boolean {
  return extents[3] > bounds.x0 && extents[0] < bounds.x1 && extents[5] > bounds.y0 && extents[2] < bounds.y1;
}

/** Bounding box of the chunks whose area is one of `areaIds` or below one of them. */
function findAreaBounds(storage: CascStorage, game: GameData, tiles: WdtTile[], areaIds: Set<number>): Bounds | undefined {
  const matches = new Map<number, boolean>();
  const isMatch = (areaId: number) => {
    let match = matches.get(areaId);
    if (match === undefined) {
      match = game.areaChain(areaId).some((a) => areaIds.has(a.id));
      matches.set(areaId, match);
    }
    return match;
  };
  let bounds: Bounds | undefined;
  for (const tile of tiles) {
    const adt = loadAdt(storage, tile);
    if (typeof adt === 'string') continue;
    adt.chunks.forEach((chunk, index) => {
      if (!chunk || !isMatch(chunk.areaId)) return;
      const x = tile.x * TILE_SIZE + (index % 16) * CHUNK_SIZE;
      const y = tile.y * TILE_SIZE + Math.floor(index / 16) * CHUNK_SIZE;
      bounds = bounds
        ? { x0: Math.min(bounds.x0, x), y0: Math.min(bounds.y0, y), x1: Math.max(bounds.x1, x + CHUNK_SIZE), y1: Math.max(bounds.y1, y + CHUNK_SIZE) }
        : { x0: x, y0: y, x1: x + CHUNK_SIZE, y1: y + CHUNK_SIZE };
    });
  }
  return bounds;
}

function progress(log: (message: string) => void, unit: string) {
  let last = -1;
  return (done: number, total: number) => {
    const percent = Math.floor((done / total) * 10) * 10;
    if (percent !== last && process.stderr.isTTY) {
      process.stderr.write(`\r  ${done}/${total} ${unit} (${percent}%)${done === total ? '\n' : ''}`);
    }
    last = percent;
    if (!process.stderr.isTTY && done === total) log(`  ${total} ${unit}`);
  };
}
