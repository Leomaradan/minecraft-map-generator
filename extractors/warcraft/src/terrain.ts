import type { CascStorage } from './casc/storage.ts';
import { EncryptedError } from './casc/blte.ts';
import { CELL_SIZE, CHUNK_SIZE, TILE_SIZE, isHole, liquidHeight, readAdt, terrainHeight, type Adt } from './formats/adt.ts';
import type { WdtTile } from './formats/wdt.ts';
import type { FluidKind, GameData } from './gameData.ts';

/** Heights are stored as Int16 quarter-yards; this marks "no data". */
export const NO_HEIGHT = -32768;
const HEIGHT_UNIT = 4;

export const FLUID_NONE = 0;
export const FLUID_KINDS: FluidKind[] = ['water', 'ocean', 'lava', 'slime'];

/** A rectangle in yards, relative to the north-west corner of tile (0, 0). */
export interface Bounds {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

/**
 * The terrain of a map sampled on a regular grid, one sample per pixel. Heights
 * (ground and fluid surface) are in yards above WoW's sea level.
 */
export class TerrainRaster {
  readonly width: number;
  readonly height: number;
  readonly bounds: Bounds;
  readonly scale: number;
  readonly ground: Int16Array;
  readonly fluid: Int16Array;
  /** 0: none, else 1 + index in FLUID_KINDS. */
  readonly fluidKind: Uint8Array;
  /** Index in `areaIds`. */
  readonly area: Uint16Array;
  readonly areaIds: number[] = [];
  private readonly areaIndex = new Map<number, number>();

  constructor(bounds: Bounds, scale: number) {
    this.bounds = bounds;
    this.scale = scale;
    this.width = Math.max(1, Math.round((bounds.x1 - bounds.x0) / scale));
    this.height = Math.max(1, Math.round((bounds.y1 - bounds.y0) / scale));
    const size = this.width * this.height;
    this.ground = new Int16Array(size).fill(NO_HEIGHT);
    this.fluid = new Int16Array(size).fill(NO_HEIGHT);
    this.fluidKind = new Uint8Array(size);
    this.area = new Uint16Array(size);
  }

  groundAt(i: number): number | undefined {
    return this.ground[i] === NO_HEIGHT ? undefined : this.ground[i] / HEIGHT_UNIT;
  }

  fluidAt(i: number): number | undefined {
    return this.fluid[i] === NO_HEIGHT ? undefined : this.fluid[i] / HEIGHT_UNIT;
  }

  private models?: { top: Int16Array; bottom: Int16Array; ceiling: Int16Array };

  /** Surfaces of the models (WMO), allocated on first use: highest, lowest, and ceiling above the ground. */
  modelLayers(): { top: Int16Array; bottom: Int16Array; ceiling: Int16Array } {
    if (!this.models) {
      const size = this.width * this.height;
      this.models = {
        top: new Int16Array(size).fill(NO_HEIGHT),
        bottom: new Int16Array(size).fill(NO_HEIGHT),
        ceiling: new Int16Array(size).fill(NO_HEIGHT),
      };
    }
    return this.models;
  }

  get hasModels(): boolean {
    return this.models !== undefined;
  }

  areaIndexOf(areaId: number): number {
    let index = this.areaIndex.get(areaId);
    if (index === undefined) {
      index = this.areaIds.length;
      this.areaIds.push(areaId);
      this.areaIndex.set(areaId, index);
    }
    return index;
  }
}

export interface RenderReport {
  tilesRendered: number;
  /** Tiles skipped because they are encrypted or not downloaded. */
  tilesSkipped: { x: number; y: number; reason: string }[];
}

/** Bounds covering whole tiles. */
export function tileBounds(tiles: { x: number; y: number }[]): Bounds {
  const xs = tiles.map((t) => t.x);
  const ys = tiles.map((t) => t.y);
  return {
    x0: Math.min(...xs) * TILE_SIZE,
    y0: Math.min(...ys) * TILE_SIZE,
    x1: (Math.max(...xs) + 1) * TILE_SIZE,
    y1: (Math.max(...ys) + 1) * TILE_SIZE,
  };
}

export function loadAdt(storage: CascStorage, tile: WdtTile): Adt | string {
  try {
    const data = storage.readFile(tile.rootAdt);
    return data ? readAdt(data) : 'not in local storage';
  } catch (error) {
    if (error instanceof EncryptedError) return 'encrypted';
    throw error;
  }
}

/** Samples every tile overlapping the raster's bounds. */
export function renderTerrain(
  raster: TerrainRaster,
  tiles: WdtTile[],
  storage: CascStorage,
  game: GameData,
  onProgress?: (done: number, total: number) => void,
  honorHoles = false,
): RenderReport {
  const { bounds, scale } = raster;
  const report: RenderReport = { tilesRendered: 0, tilesSkipped: [] };
  const overlapping = tiles.filter(
    (t) => (t.x + 1) * TILE_SIZE > bounds.x0 && t.x * TILE_SIZE < bounds.x1 && (t.y + 1) * TILE_SIZE > bounds.y0 && t.y * TILE_SIZE < bounds.y1,
  );

  overlapping.forEach((tile, n) => {
    onProgress?.(n, overlapping.length);
    const adt = loadAdt(storage, tile);
    if (typeof adt === 'string') {
      report.tilesSkipped.push({ x: tile.x, y: tile.y, reason: adt });
      return;
    }
    const tileX = tile.x * TILE_SIZE;
    const tileY = tile.y * TILE_SIZE;
    // Pixels whose center falls inside this tile.
    const px0 = Math.max(0, Math.ceil((tileX - bounds.x0) / scale - 0.5));
    const px1 = Math.min(raster.width, Math.ceil((tileX + TILE_SIZE - bounds.x0) / scale - 0.5));
    const py0 = Math.max(0, Math.ceil((tileY - bounds.y0) / scale - 0.5));
    const py1 = Math.min(raster.height, Math.ceil((tileY + TILE_SIZE - bounds.y0) / scale - 0.5));
    for (let py = py0; py < py1; py++) {
      const ly = bounds.y0 + (py + 0.5) * scale - tileY;
      for (let px = px0; px < px1; px++) {
        const lx = bounds.x0 + (px + 0.5) * scale - tileX;
        samplePixel(raster, py * raster.width + px, adt, game, lx, ly, honorHoles);
      }
    }
    report.tilesRendered++;
  });
  onProgress?.(overlapping.length, overlapping.length);
  return report;
}

function samplePixel(raster: TerrainRaster, i: number, adt: Adt, game: GameData, lx: number, ly: number, honorHoles: boolean): void {
  const chunkX = Math.min(15, Math.floor(lx / CHUNK_SIZE));
  const chunkY = Math.min(15, Math.floor(ly / CHUNK_SIZE));
  const chunk = adt.chunks[chunkY * 16 + chunkX];
  if (!chunk) return;
  const fx = Math.min(7.9999, (lx - chunkX * CHUNK_SIZE) / CELL_SIZE);
  const fy = Math.min(7.9999, (ly - chunkY * CHUNK_SIZE) / CELL_SIZE);
  const col = Math.floor(fx);
  const row = Math.floor(fy);
  const u = fx - col;
  const v = fy - row;

  raster.area[i] = raster.areaIndexOf(chunk.areaId);
  if (honorHoles && isHole(chunk, col, row)) return;
  raster.ground[i] = toUnits(terrainHeight(chunk.heights, col, row, u, v));

  // Keep the highest surface; lava wins over water, as in the mod.
  let best: number | undefined;
  let bestKind = FLUID_NONE;
  for (const layer of chunk.liquids) {
    const liquid = game.liquid(layer.liquidType);
    const format = liquid.kind === 'ocean' ? 2 : layer.objectOrFormat < 42 ? layer.objectOrFormat : liquid.format;
    const h = liquidHeight(adt, layer, format, col, row, u, v);
    if (h === undefined || !Number.isFinite(h)) continue;
    const kind = FLUID_KINDS.indexOf(liquid.kind) + 1;
    const isLava = liquid.kind === 'lava';
    const bestIsLava = bestKind === FLUID_KINDS.indexOf('lava') + 1;
    if (best === undefined || (isLava && !bestIsLava) || (isLava === bestIsLava && h > best)) {
      best = h;
      bestKind = kind;
    }
  }
  if (best !== undefined) {
    raster.fluid[i] = toUnits(best);
    raster.fluidKind[i] = bestKind;
  }
}

function toUnits(yards: number): number {
  return Math.max(-32767, Math.min(32767, Math.round(yards * HEIGHT_UNIT)));
}
