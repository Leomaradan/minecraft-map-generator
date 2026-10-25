import type { CascStorage } from './casc/storage.ts';
import { EncryptedError } from './casc/blte.ts';
import type { WdtTile } from './formats/wdt.ts';
import { placementTransform, readWmoGroup, readWmoPlacements, readWmoRoot, type WmoGroup, type WmoPlacement } from './formats/wmo.ts';
import { NO_HEIGHT, type Bounds, type TerrainRaster } from './terrain.ts';

/**
 * Sign of the 2D cross product (map x, map y) of a triangle whose face points up.
 * The model-to-map transform swaps two axes, which mirrors the WMO winding.
 */
export const UP_FACING_SIGN = -1;

/** WMO placements of the tiles, without the duplicates listed by every tile they overlap. */
export function collectPlacements(storage: CascStorage, tiles: WdtTile[]): WmoPlacement[] {
  const placements = new Map<number, WmoPlacement>();
  for (const tile of tiles) {
    const data = tile.obj0Adt ? readOptional(storage, tile.obj0Adt) : undefined;
    if (!data) continue;
    for (const placement of readWmoPlacements(data)) placements.set(placement.uniqueId, placement);
  }
  return [...placements.values()];
}

export interface ModelOptions {
  /** Compute the ceiling: the lowest down-facing surface above the ground plus `clearance` yards. */
  ceiling: boolean;
  clearance: number;
}

export interface ModelReport {
  placements: number;
  triangles: number;
  skipped: number;
}

/**
 * Rasterizes WMO meshes: highest and lowest surface of every pixel (modelTop,
 * modelBottom) and, on request, the ceiling above the terrain.
 */
export function rasterizeModels(
  raster: TerrainRaster,
  placements: WmoPlacement[],
  storage: CascStorage,
  options: ModelOptions,
  onProgress?: (done: number, total: number) => void,
): ModelReport {
  const { top, bottom, ceiling } = raster.modelLayers();
  const report: ModelReport = { placements: 0, triangles: 0, skipped: 0 };
  const groupCache = new Map<number, WmoGroup[]>();
  const inside = placements.filter((p) => overlaps(p.extents, raster.bounds));

  inside.forEach((placement, n) => {
    onProgress?.(n, inside.length);
    let groups = groupCache.get(placement.fileDataId);
    if (!groups) {
      const root = readOptional(storage, placement.fileDataId);
      groups = root ? readWmoRoot(root).groups.map((id) => readOptional(storage, id)).filter((d) => d !== undefined).map((d) => readWmoGroup(d)) : [];
      groupCache.set(placement.fileDataId, groups);
    }
    if (groups.length === 0) {
      report.skipped++;
      return;
    }
    const transform = placementTransform(placement);
    for (const group of groups) {
      const v = group.vertices;
      // Vertices in pixel coordinates (centers at integers) and quarter-yard heights.
      const points = new Float64Array((v.length / 3) * 3);
      for (let i = 0; i < v.length; i += 3) {
        const [x, y, h] = transform(v[i], v[i + 1], v[i + 2]);
        points[i] = (x - raster.bounds.x0) / raster.scale - 0.5;
        points[i + 1] = (y - raster.bounds.y0) / raster.scale - 0.5;
        points[i + 2] = h * 4;
      }
      for (let t = 0; t < group.indices.length; t += 3) {
        drawTriangle(raster, points, group.indices[t] * 3, group.indices[t + 1] * 3, group.indices[t + 2] * 3, top, bottom, options.ceiling ? ceiling : undefined, options.clearance * 4);
      }
      report.triangles += group.indices.length / 3;
    }
    report.placements++;
  });
  onProgress?.(inside.length, inside.length);
  return report;
}

function drawTriangle(
  raster: TerrainRaster,
  p: Float64Array,
  a: number,
  b: number,
  c: number,
  top: Int16Array,
  bottom: Int16Array,
  ceiling: Int16Array | undefined,
  clearance: number,
): void {
  const [ax, ay, az, bx, by, bz, cx, cy, cz] = [p[a], p[a + 1], p[a + 2], p[b], p[b + 1], p[b + 2], p[c], p[c + 1], p[c + 2]];
  const area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
  if (Math.abs(area) < 1e-9) return; // vertical or degenerate
  const facesDown = Math.sign(area) !== UP_FACING_SIGN;
  const x0 = Math.max(0, Math.ceil(Math.min(ax, bx, cx)));
  const x1 = Math.min(raster.width - 1, Math.floor(Math.max(ax, bx, cx)));
  const y0 = Math.max(0, Math.ceil(Math.min(ay, by, cy)));
  const y1 = Math.min(raster.height - 1, Math.floor(Math.max(ay, by, cy)));
  for (let y = y0; y <= y1; y++) {
    for (let x = x0; x <= x1; x++) {
      const w0 = ((bx - x) * (cy - y) - (by - y) * (cx - x)) / area;
      const w1 = ((cx - x) * (ay - y) - (cy - y) * (ax - x)) / area;
      const w2 = 1 - w0 - w1;
      if (w0 < 0 || w1 < 0 || w2 < 0) continue;
      const h = Math.max(-32767, Math.min(32767, Math.round(w0 * az + w1 * bz + w2 * cz)));
      const i = y * raster.width + x;
      if (top[i] === NO_HEIGHT || h > top[i]) top[i] = h;
      if (bottom[i] === NO_HEIGHT || h < bottom[i]) bottom[i] = h;
      if (ceiling && facesDown) {
        const ground = raster.ground[i];
        if ((ground === NO_HEIGHT || h > ground + clearance) && (ceiling[i] === NO_HEIGHT || h < ceiling[i])) ceiling[i] = h;
      }
    }
  }
}

function overlaps(extents: WmoPlacement['extents'], bounds: Bounds): boolean {
  // Extents are in placement space: x and z on the map.
  return extents[3] > bounds.x0 && extents[0] < bounds.x1 && extents[5] > bounds.y0 && extents[2] < bounds.y1;
}

function readOptional(storage: CascStorage, fileDataId: number): Buffer | undefined {
  try {
    return storage.readFile(fileDataId);
  } catch (error) {
    if (error instanceof EncryptedError) return undefined;
    throw error;
  }
}
