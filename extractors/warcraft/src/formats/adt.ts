import { readChunks } from './chunks.ts';

/** Size of an ADT tile in yards. A map is 64x64 tiles, a tile 16x16 chunks, a chunk 8x8 cells. */
export const TILE_SIZE = 1600 / 3;
export const CHUNK_SIZE = TILE_SIZE / 16;
export const CELL_SIZE = CHUNK_SIZE / 8;

export interface LiquidLayer {
  /** LiquidType.db2 ID. */
  liquidType: number;
  /** Liquid vertex format, or a LiquidObject ID (>= 42) when the format comes from the liquid material. */
  objectOrFormat: number;
  minHeight: number;
  maxHeight: number;
  xOffset: number;
  yOffset: number;
  width: number;
  height: number;
  /** One bit per cell (row-major, width x height); undefined when every cell has liquid. */
  exists: Buffer | undefined;
  /** Absolute offset of the vertex data in the ADT, 0 when the surface is flat at minHeight. */
  vertexData: number;
}

export interface AdtChunk {
  areaId: number;
  /** Holes (no terrain) by cell, bit = row * 8 + col: cells 0-31, then 32-63. */
  holes: [number, number];
  /** 145 absolute heights: rows of 9 outer vertices interleaved with rows of 8 inner (cell center) vertices. */
  heights: Float32Array;
  liquids: LiquidLayer[];
}

export interface Adt {
  data: Buffer;
  /** 256 chunks, index = chunkY * 16 + chunkX; undefined if missing. */
  chunks: (AdtChunk | undefined)[];
}

/** Reads the terrain (MCNK heights, area IDs) and liquids (MH2O) of a root ADT. */
export function readAdt(data: Buffer): Adt {
  const chunks: (AdtChunk | undefined)[] = new Array(256);
  let mh2o: { start: number; size: number } | undefined;

  for (const chunk of readChunks(data)) {
    if (chunk.id === 'MH2O') mh2o = chunk;
    if (chunk.id !== 'MCNK') continue;
    const h = chunk.start;
    const indexX = data.readUInt32LE(h + 4);
    const indexY = data.readUInt32LE(h + 8);
    const areaId = data.readUInt32LE(h + 0x34);
    const holes = readHoles(data, h);
    const baseHeight = data.readFloatLE(h + 0x68 + 8);
    const heights = new Float32Array(145).fill(baseHeight);
    for (const sub of readChunks(data, h + 128, h + chunk.size)) {
      if (sub.id === 'MCVT') {
        for (let i = 0; i < 145; i++) heights[i] = baseHeight + data.readFloatLE(sub.start + i * 4);
      }
    }
    chunks[indexY * 16 + indexX] = { areaId, holes, heights, liquids: [] };
  }

  if (mh2o) readLiquids(data, mh2o.start, chunks);
  return { data, chunks };
}

const HIGH_RES_HOLES = 0x10000;

/** Holes of an MCNK: an 8x8 bitmap (high resolution), or 4x4 bits covering 2x2 cells each. */
function readHoles(data: Buffer, header: number): [number, number] {
  if (data.readUInt32LE(header) & HIGH_RES_HOLES) {
    return [data.readUInt32LE(header + 0x14), data.readUInt32LE(header + 0x18)];
  }
  const low = data.readUInt16LE(header + 0x3c);
  const holes: [number, number] = [0, 0];
  for (let bit = 0; bit < 16; bit++) {
    if (!(low & (1 << bit))) continue;
    const row = Math.floor(bit / 4) * 2;
    const col = (bit % 4) * 2;
    for (const cell of [row * 8 + col, row * 8 + col + 1, (row + 1) * 8 + col, (row + 1) * 8 + col + 1]) {
      holes[cell >> 5] = (holes[cell >> 5] | (1 << (cell & 31))) >>> 0;
    }
  }
  return holes;
}

export function isHole(chunk: AdtChunk, col: number, row: number): boolean {
  const cell = row * 8 + col;
  return ((chunk.holes[cell >> 5] >>> (cell & 31)) & 1) === 1;
}

function readLiquids(data: Buffer, base: number, chunks: (AdtChunk | undefined)[]): void {
  for (let c = 0; c < 256; c++) {
    const chunk = chunks[c];
    const header = base + c * 12;
    const instancesOffset = data.readUInt32LE(header);
    const layerCount = data.readUInt32LE(header + 4);
    if (!chunk || layerCount === 0 || instancesOffset === 0) continue;
    for (let l = 0; l < layerCount; l++) {
      const p = base + instancesOffset + l * 24;
      const width = data[p + 14];
      const height = data[p + 15];
      const existsOffset = data.readUInt32LE(p + 16);
      const vertexOffset = data.readUInt32LE(p + 20);
      chunk.liquids.push({
        liquidType: data.readUInt16LE(p),
        objectOrFormat: data.readUInt16LE(p + 2),
        minHeight: data.readFloatLE(p + 4),
        maxHeight: data.readFloatLE(p + 8),
        xOffset: data[p + 12],
        yOffset: data[p + 13],
        width,
        height,
        exists: existsOffset ? data.subarray(base + existsOffset, base + existsOffset + Math.ceil((width * height) / 8)) : undefined,
        vertexData: vertexOffset ? base + vertexOffset : 0,
      });
    }
  }
}

/**
 * Height of the terrain at (u, v) in cell (col, row) of a chunk, u and v in [0, 1).
 * WoW cells are 4 triangles fanning from the center vertex.
 */
export function terrainHeight(heights: Float32Array, col: number, row: number, u: number, v: number): number {
  const tl = heights[row * 17 + col];
  const tr = heights[row * 17 + col + 1];
  const bl = heights[(row + 1) * 17 + col];
  const br = heights[(row + 1) * 17 + col + 1];
  const center = heights[row * 17 + 9 + col];
  // In each triangle (corner a, corner b, center), the center weight is 1 - 2 * distance to the edge.
  if (v <= u && v <= 1 - u) {
    const wc = 2 * v;
    return center * wc + tl * (1 - u - v) + tr * (u - v); // top
  }
  if (v >= u && v >= 1 - u) {
    const wc = 2 * (1 - v);
    return center * wc + bl * (v - u) + br * (u + v - 1); // bottom
  }
  if (u < v) {
    const wc = 2 * u;
    return center * wc + tl * (1 - u - v) + bl * (v - u); // left
  }
  const wc = 2 * (1 - u);
  return center * wc + tr * (u - v) + br * (u + v - 1); // right
}

/**
 * Height of a liquid layer at (u, v) in cell (col, row), or undefined when the
 * layer doesn't cover that cell. `format` is the liquid vertex format: 0, 1 and 3
 * store heights, 2 (ocean) only depths, so its surface is flat.
 */
export function liquidHeight(adt: Adt, layer: LiquidLayer, format: number, col: number, row: number, u: number, v: number): number | undefined {
  const x = col - layer.xOffset;
  const y = row - layer.yOffset;
  if (x < 0 || y < 0 || x >= layer.width || y >= layer.height) return undefined;
  if (layer.exists) {
    const bit = y * layer.width + x;
    if (!(layer.exists[bit >> 3] & (1 << (bit & 7)))) return undefined;
  }
  if (!layer.vertexData || format === 2) return layer.minHeight;
  const stride = layer.width + 1;
  const at = (vx: number, vy: number) => {
    const h = adt.data.readFloatLE(layer.vertexData + (vy * stride + vx) * 4);
    // Guards against a wrong vertex format: heights must lie in the layer's range.
    return h >= layer.minHeight - 1 && h <= layer.maxHeight + 1 ? h : layer.minHeight;
  };
  const top = at(x, y) * (1 - u) + at(x + 1, y) * u;
  const bottom = at(x, y + 1) * (1 - u) + at(x + 1, y + 1) * u;
  return top * (1 - v) + bottom * v;
}
