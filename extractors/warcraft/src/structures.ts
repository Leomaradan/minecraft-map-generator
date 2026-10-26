import { createHash } from 'node:crypto';
import type { CascStorage } from './casc/storage.ts';
import { EncryptedError } from './casc/blte.ts';
import { blpAverageColor, type Rgb } from './formats/blp.ts';
import { placementTransform, readWmoGroup, readWmoRoot, type WmoPlacement } from './formats/wmo.ts';
import { gzipNbt, structureNbt, type StructureBlock } from './output/nbt.ts';
import { DEFAULT_BLOCK, nearestBlock } from './palette.ts';
import { NO_HEIGHT, type TerrainRaster } from './terrain.ts';

export interface StructureOptions {
  verticalScale: number;
  seaLevel: number;
  /** World X, Z of pixel (0, 0). */
  origin: [number, number];
}

export interface StructureFile {
  path: string;
  /** Gzipped NBT. */
  data: Buffer;
}

export interface TemplatePlacement {
  path: string;
  /** World coordinates of the template's lowest corner. */
  x: number;
  y: number;
  z: number;
}

export interface StructureSet {
  files: StructureFile[];
  placements: TemplatePlacement[];
  buildings: number;
  blocks: number;
  /** Size of the NBT files in the ZIP. */
  bytes: number;
  largest?: { fileDataId: number; blocks: number };
  skipped: number;
}

// Blocks deeper than this under the terrain can't be seen (the ground around them is solid).
const BURIED_DEPTH = 2;
// Pieces are cut along Minecraft chunks: the mod pastes templates chunk by chunk, and
// walks every block of a template for each chunk it touches.
const CHUNK = 16;
const Y_OFFSET = 2048;
const Y_RANGE = 4096;

/**
 * Turns WMO models into structure templates: their faces are voxelized at the map
 * scale (hollow shells: walls, floors, roofs), each block taking the color of the
 * face's texture, then cut into chunk-sized pieces.
 */
export function buildStructures(
  raster: TerrainRaster,
  placements: WmoPlacement[],
  storage: CascStorage,
  options: StructureOptions,
  onProgress?: (done: number, total: number) => void,
): StructureSet {
  const set: StructureSet = { files: [], placements: [], buildings: 0, blocks: 0, bytes: 0, skipped: 0 };
  const written = new Map<string, string>(); // content hash -> path
  const textures = new Map<number, Rgb | undefined>();
  const inside = placements.filter(
    (p) => p.extents[3] > raster.bounds.x0 && p.extents[0] < raster.bounds.x1 && p.extents[5] > raster.bounds.y0 && p.extents[2] < raster.bounds.y1,
  );

  inside.forEach((placement, n) => {
    onProgress?.(n, inside.length);
    const voxels = voxelize(raster, placement, storage, options, textures);
    if (!voxels) {
      set.skipped++;
      return;
    }
    if (voxels.count === 0) return;
    set.buildings++;
    set.blocks += voxels.count;
    if (!set.largest || voxels.count > set.largest.blocks) set.largest = { fileDataId: placement.fileDataId, blocks: voxels.count };

    for (const piece of voxels.pieces(options.origin)) {
      const nbt = structureNbt(piece.size, piece.palette, piece.blocks);
      const hash = createHash('sha1').update(nbt).digest('hex').slice(0, 12);
      let path = written.get(hash);
      if (!path) {
        path = `structures/wmo${placement.fileDataId}_${hash}.nbt`;
        const data = gzipNbt(nbt);
        set.files.push({ path, data });
        set.bytes += data.length;
        written.set(hash, path);
      }
      set.placements.push({ path, x: options.origin[0] + piece.x, y: piece.y, z: options.origin[1] + piece.z });
    }
  });
  onProgress?.(inside.length, inside.length);
  return set;
}

class Voxels {
  private readonly index = new Map<number, number>();
  private r = new Float32Array(1024);
  private g = new Float32Array(1024);
  private b = new Float32Array(1024);
  private weight = new Float32Array(1024);
  private keys = new Float64Array(1024);
  count = 0;
  private readonly height: number;

  constructor(height: number) {
    this.height = height;
  }

  key(x: number, y: number, z: number): number {
    return (x * this.height + z) * Y_RANGE + (y + Y_OFFSET);
  }

  add(x: number, y: number, z: number, color: Rgb | undefined): void {
    const key = this.key(x, y, z);
    let i = this.index.get(key);
    if (i === undefined) {
      i = this.count++;
      if (i >= this.keys.length) this.grow();
      this.keys[i] = key;
      this.index.set(key, i);
    }
    if (color) {
      this.r[i] += color.r;
      this.g[i] += color.g;
      this.b[i] += color.b;
      this.weight[i]++;
    }
  }

  private grow(): void {
    const size = this.keys.length * 2;
    const copy = <T extends Float32Array | Float64Array>(a: T, make: (n: number) => T) => {
      const next = make(size);
      next.set(a);
      return next;
    };
    this.r = copy(this.r, (n) => new Float32Array(n));
    this.g = copy(this.g, (n) => new Float32Array(n));
    this.b = copy(this.b, (n) => new Float32Array(n));
    this.weight = copy(this.weight, (n) => new Float32Array(n));
    this.keys = copy(this.keys, (n) => new Float64Array(n));
  }

  private position(i: number): [number, number, number] {
    const key = this.keys[i];
    const y = (key % Y_RANGE) - Y_OFFSET;
    const xz = Math.floor(key / Y_RANGE);
    return [Math.floor(xz / this.height), y, xz % this.height];
  }

  /** Faces without a texture (collision meshes) take the color of their neighbors. */
  private fillColors(): void {
    for (let pass = 0; pass < 4; pass++) {
      const updates: [number, number, number, number][] = [];
      for (let i = 0; i < this.count; i++) {
        if (this.weight[i] > 0) continue;
        const [x, y, z] = this.position(i);
        let r = 0;
        let g = 0;
        let b = 0;
        let n = 0;
        for (const [dx, dy, dz] of [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]]) {
          // Out-of-range y or z would wrap onto a voxel of another column.
          if (z + dz < 0 || z + dz >= this.height || y + dy < -Y_OFFSET || y + dy >= Y_RANGE - Y_OFFSET) continue;
          const j = this.index.get(this.key(x + dx, y + dy, z + dz));
          if (j === undefined || this.weight[j] === 0) continue;
          r += this.r[j] / this.weight[j];
          g += this.g[j] / this.weight[j];
          b += this.b[j] / this.weight[j];
          n++;
        }
        if (n > 0) updates.push([i, r / n, g / n, b / n]);
      }
      if (updates.length === 0) break;
      for (const [i, r, g, b] of updates) {
        this.r[i] = r;
        this.g[i] = g;
        this.b[i] = b;
        this.weight[i] = 1;
      }
    }
  }

  /** Chunk-aligned pieces, positions relative to their lowest corner. */
  *pieces(origin: [number, number]): Generator<{ x: number; y: number; z: number; size: [number, number, number]; palette: string[]; blocks: StructureBlock[] }> {
    this.fillColors();
    const byChunk = new Map<string, number[]>();
    for (let i = 0; i < this.count; i++) {
      const [x, , z] = this.position(i);
      const key = `${Math.floor((origin[0] + x) / CHUNK)},${Math.floor((origin[1] + z) / CHUNK)}`;
      let list = byChunk.get(key);
      if (!list) byChunk.set(key, (list = []));
      list.push(i);
    }
    for (const list of byChunk.values()) {
      const positions = list.map((i) => this.position(i));
      const min = [Infinity, Infinity, Infinity];
      const max = [-Infinity, -Infinity, -Infinity];
      for (const p of positions) {
        for (let a = 0; a < 3; a++) {
          if (p[a] < min[a]) min[a] = p[a];
          if (p[a] > max[a]) max[a] = p[a];
        }
      }
      const palette: string[] = [];
      const states = new Map<string, number>();
      const blocks = list.map((i, k) => {
        const name = this.weight[i] > 0 ? nearestBlock({ r: this.r[i] / this.weight[i], g: this.g[i] / this.weight[i], b: this.b[i] / this.weight[i] }) : DEFAULT_BLOCK;
        let state = states.get(name);
        if (state === undefined) {
          state = palette.length;
          palette.push(name);
          states.set(name, state);
        }
        const [x, y, z] = positions[k];
        return { x: x - min[0], y: y - min[1], z: z - min[2], state };
      });
      yield {
        x: min[0],
        y: min[1],
        z: min[2],
        size: [max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1],
        palette,
        blocks,
      };
    }
  }
}

/** Voxels of one placed WMO, or undefined when it can't be read. */
function voxelize(
  raster: TerrainRaster,
  placement: WmoPlacement,
  storage: CascStorage,
  options: StructureOptions,
  textures: Map<number, Rgb | undefined>,
): Voxels | undefined {
  const rootData = readOptional(storage, placement.fileDataId);
  if (!rootData) return undefined;
  const root = readWmoRoot(rootData);
  const colors = root.materialTextures.map((id) => textureColor(storage, id, textures));
  const transform = placementTransform(placement);
  const voxels = new Voxels(raster.height);
  const { width, height, bounds, scale } = raster;
  let readable = false;

  for (const groupId of root.groups) {
    const data = readOptional(storage, groupId);
    if (!data) continue;
    readable = true;
    const group = readWmoGroup(data, 'visible');
    const v = group.vertices;
    // Vertices in voxel units: x and z in pixels, y in world blocks.
    const points = new Float64Array(v.length);
    for (let i = 0; i < v.length; i += 3) {
      const [x, y, h] = transform(v[i], v[i + 1], v[i + 2]);
      points[i] = (x - bounds.x0) / scale;
      points[i + 1] = options.seaLevel + h / options.verticalScale;
      points[i + 2] = (y - bounds.y0) / scale;
    }
    for (let t = 0; t < group.indices.length / 3; t++) {
      const [a, b, c] = [group.indices[t * 3] * 3, group.indices[t * 3 + 1] * 3, group.indices[t * 3 + 2] * 3];
      const color = colors[group.materials[t]];
      // Sample the triangle finely enough to touch every voxel it crosses.
      const edge = Math.max(dist(points, a, b), dist(points, b, c), dist(points, c, a));
      const steps = Math.max(1, Math.ceil(edge * 1.5));
      for (let i = 0; i <= steps; i++) {
        for (let j = 0; j <= steps - i; j++) {
          const u = i / steps;
          const w = j / steps;
          const x = Math.floor(points[a] + (points[b] - points[a]) * u + (points[c] - points[a]) * w);
          const z = Math.floor(points[a + 2] + (points[b + 2] - points[a + 2]) * u + (points[c + 2] - points[a + 2]) * w);
          if (x < 0 || z < 0 || x >= width || z >= height) continue;
          const y = Math.floor(points[a + 1] + (points[b + 1] - points[a + 1]) * u + (points[c + 1] - points[a + 1]) * w);
          if (y < -2032 || y > 2031) continue;
          const ground = raster.ground[z * width + x];
          if (ground !== NO_HEIGHT && y < options.seaLevel + ground / 4 / options.verticalScale - BURIED_DEPTH) continue;
          voxels.add(x, y, z, color);
        }
      }
    }
  }
  return readable ? voxels : undefined;
}

function dist(p: Float64Array, a: number, b: number): number {
  return Math.hypot(p[a] - p[b], p[a + 1] - p[b + 1], p[a + 2] - p[b + 2]);
}

function textureColor(storage: CascStorage, fileDataId: number, cache: Map<number, Rgb | undefined>): Rgb | undefined {
  if (!fileDataId) return undefined;
  if (!cache.has(fileDataId)) {
    const data = readOptional(storage, fileDataId);
    cache.set(fileDataId, data ? blpAverageColor(data) : undefined);
  }
  return cache.get(fileDataId);
}

function readOptional(storage: CascStorage, fileDataId: number): Buffer | undefined {
  try {
    return storage.readFile(fileDataId);
  } catch (error) {
    if (error instanceof EncryptedError) return undefined;
    throw error;
  }
}
