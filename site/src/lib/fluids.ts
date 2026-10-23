import type { FluidBody, Raster } from './project';
import type { HeightMapping } from './heightMapping';
import { fromY, maxValue, toY } from './heightMapping';
import { rasterizeRiver, resolveRiver } from './rivers';

export interface FluidResult {
  water: Uint16Array;
  lava: Uint16Array;
  /** 1 where the computed surface is not above the ground (the mod places nothing there). */
  low: Uint8Array;
  /** Surface level in raster units for each painted pixel (0 = none, or a dry pixel of a lake zone). */
  levels: Float32Array;
  /** Where to write each body's number: (body id, x, z) per labelled piece. */
  anchors: Float32Array;
  /** Flow arrows on free flows, where the surface drops a block: (x, z, dx, dz) with (dx, dz) a unit vector. */
  arrows: Float32Array;
}

const NEIGHBORS: ReadonlyArray<readonly [number, number]> = [[1, 0], [-1, 0], [0, 1], [0, -1]];

const RIVER_WORK_BUDGET = 3e7;

/** Labels the 4-connected components of the painted pixels (same body id). Pixels are grouped by component. */
export function labelComponents(labels: Uint16Array, width: number) {
  const n = labels.length;
  const order = new Int32Array(n);
  const starts: number[] = [];
  const seen = new Uint8Array(n);
  const stack = new Int32Array(n);
  let count = 0;
  for (let start = 0; start < n; start++) {
    if (labels[start] === 0 || seen[start]) continue;
    const label = labels[start];
    starts.push(count);
    let top = 0;
    stack[top++] = start;
    seen[start] = 1;
    while (top > 0) {
      const p = stack[--top];
      order[count++] = p;
      const x = p % width;
      if (x > 0 && labels[p - 1] === label && !seen[p - 1]) { seen[p - 1] = 1; stack[top++] = p - 1; }
      if (x < width - 1 && labels[p + 1] === label && !seen[p + 1]) { seen[p + 1] = 1; stack[top++] = p + 1; }
      if (p >= width && labels[p - width] === label && !seen[p - width]) { seen[p - width] = 1; stack[top++] = p - width; }
      if (p + width < n && labels[p + width] === label && !seen[p + width]) { seen[p + width] = 1; stack[top++] = p + width; }
    }
  }
  starts.push(count);
  return { order, starts };
}

/** Lowest ground among the unpainted neighbours of a component, or -1 if it has none. */
function lowestBank(order: Int32Array, from: number, to: number, labels: Uint16Array, ground: Uint16Array, width: number, height: number): number {
  let lowest = -1;
  for (let k = from; k < to; k++) {
    const p = order[k];
    const x = p % width;
    const y = (p - x) / width;
    for (const [dx, dy] of NEIGHBORS) {
      const nx = x + dx;
      const ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
      const q = ny * width + nx;
      if (labels[q] === 0 && (lowest < 0 || ground[q] < lowest)) lowest = ground[q];
    }
  }
  return lowest;
}

/**
 * Smooth surface between the bank heights: each pixel is the average of its neighbours, with the
 * banks fixed (Laplace equation, successive over-relaxation). Writes into `value`.
 */
function solveRiver(order: Int32Array, from: number, to: number, labels: Uint16Array, ground: Uint16Array, width: number, height: number, value: Float32Array): void {
  const body = labels[order[from]];
  const omega = 1.8;
  const size = to - from;
  const maxIter = Math.max(20, Math.min(4000, Math.floor(RIVER_WORK_BUDGET / size)));
  for (let iter = 0; iter < maxIter; iter++) {
    let delta = 0;
    for (let k = from; k < to; k++) {
      const p = order[k];
      const x = p % width;
      const y = (p - x) / width;
      let sum = 0;
      let count = 0;
      for (const [dx, dy] of NEIGHBORS) {
        const nx = x + dx;
        const ny = y + dy;
        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
        const q = ny * width + nx;
        if (labels[q] === body) {
          sum += value[q];
          count++;
        } else if (labels[q] === 0) {
          sum += ground[q];
          count++;
        }
      }
      if (count === 0) continue;
      const next = value[p] + omega * (sum / count - value[p]);
      delta = Math.max(delta, Math.abs(next - value[p]));
      value[p] = next;
    }
    if (delta < 0.005) break;
  }
}

/** Lowest ground on the edge of a lake zone, where the water would spill out (-1 when the zone is empty). */
export function spillLevel(labels: Uint16Array, id: number, ground: Uint16Array, width: number): number {
  const n = labels.length;
  let lowest = -1;
  for (let p = 0; p < n; p++) {
    if (labels[p] !== id) continue;
    const x = p % width;
    const edge = x === 0 || x === width - 1 || p < width || p + width >= n
      || labels[p - 1] !== id || labels[p + 1] !== id || labels[p - width] !== id || labels[p + width] !== id;
    if (edge && (lowest < 0 || ground[p] < lowest)) lowest = ground[p];
  }
  return lowest;
}

/** Smallest piece of a body that still gets its own number on the map. */
const ANCHOR_MIN_PIXELS = 16;

/** One anchor per piece: the pixel of the piece closest to its centroid. The largest piece of a body is always labelled. */
function findAnchors(order: Int32Array, starts: number[], labels: Uint16Array, width: number): Float32Array {
  const out: number[] = [];
  const largest = new Map<number, { size: number; at: [number, number] }>();
  const labelled = new Set<number>();
  for (let c = 0; c + 1 < starts.length; c++) {
    const from = starts[c];
    const to = starts[c + 1];
    const id = labels[order[from]];
    let sx = 0;
    let sz = 0;
    for (let k = from; k < to; k++) {
      sx += order[k] % width;
      sz += Math.floor(order[k] / width);
    }
    const cx = sx / (to - from);
    const cz = sz / (to - from);
    let best = Infinity;
    let at: [number, number] = [0, 0];
    for (let k = from; k < to; k++) {
      const x = order[k] % width;
      const z = Math.floor(order[k] / width);
      const d = (x - cx) ** 2 + (z - cz) ** 2;
      if (d < best) {
        best = d;
        at = [x + 0.5, z + 0.5];
      }
    }
    if (to - from >= ANCHOR_MIN_PIXELS) {
      out.push(id, at[0], at[1]);
      labelled.add(id);
    }
    if ((largest.get(id)?.size ?? 0) < to - from) largest.set(id, { size: to - from, at });
  }
  for (const [id, l] of largest) if (!labelled.has(id)) out.push(id, l.at[0], l.at[1]);
  return Float32Array.from(out);
}

/** Size in pixels of the cells that get at most one flow arrow each. */
export const ARROW_CELL = 8;

/**
 * Flow arrows: wherever two neighbouring pixels of a free flow have surfaces in different blocks, the
 * step points from the higher to the lower one. Steps are averaged per cell and per river.
 */
function findArrows(labels: Uint16Array, levels: Float32Array, rivers: Set<number>, mapping: HeightMapping, width: number, height: number): Float32Array {
  const acc = new Map<number, number[]>();
  const block = (p: number) => Math.floor(mapping.baseY + levels[p] * mapping.scale);
  const cellsW = Math.ceil(width / ARROW_CELL);
  const add = (p: number, q: number, dx: number, dz: number) => {
    const id = labels[p];
    const bp = block(p);
    const bq = block(q);
    if (bp === bq) return;
    const sign = bp > bq ? 1 : -1;
    const x = (p % width) + 0.5 + dx / 2;
    const z = Math.floor(p / width) + 0.5 + dz / 2;
    const key = (Math.floor(z / ARROW_CELL) * cellsW + Math.floor(x / ARROW_CELL)) * 65536 + id;
    let a = acc.get(key);
    if (!a) acc.set(key, (a = [0, 0, 0, 0, 0]));
    a[0] += x;
    a[1] += z;
    a[2] += dx * sign;
    a[3] += dz * sign;
    a[4]++;
  };
  for (let p = 0; p < labels.length; p++) {
    if (!rivers.has(labels[p])) continue;
    const x = p % width;
    if (x < width - 1 && labels[p + 1] === labels[p]) add(p, p + 1, 1, 0);
    if (p + width < width * height && labels[p + width] === labels[p]) add(p, p + width, 0, 1);
  }
  const out: number[] = [];
  for (const [sx, sz, dx, dz, count] of acc.values()) {
    const len = Math.hypot(dx, dz);
    if (len < 1e-6) continue;
    out.push(sx / count, sz / count, dx / len, dz / len);
  }
  return Float32Array.from(out);
}

/** Computes the water and lava rasters from the painted bodies. */
export function solveFluids(
  labels: Uint16Array,
  rawLevels: Uint16Array,
  bodies: FluidBody[],
  heightmap: Raster,
  mapping: HeightMapping,
): FluidResult {
  const { width, height, data: ground } = heightmap;
  const n = width * height;
  const max = maxValue(mapping.bitDepth);
  const water = new Uint16Array(n);
  const lava = new Uint16Array(n);
  const low = new Uint8Array(n);
  const levels = new Float32Array(n);
  const byId = new Map(bodies.map((b) => [b.id, b]));
  const { order, starts } = labelComponents(labels, width);
  const value = new Float32Array(n);
  const rivers = new Set<number>();
  for (let c = 0; c + 1 < starts.length; c++) {
    const from = starts[c];
    const to = starts[c + 1];
    const body = byId.get(labels[order[from]]);
    if (!body) continue;
    const target = body.fluid === 'water' ? water : lava;
    if (body.mode === 'lake') {
      // The zone is filled where the ground is below the surface; the rest of the zone stays dry.
      if (body.level === undefined) continue;
      const surface = fromY(mapping, body.level);
      for (let k = from; k < to; k++) {
        const p = order[k];
        if (ground[p] >= surface) continue;
        levels[p] = surface;
        target[p] = surface;
      }
      continue;
    }
    if (body.mode === 'river') continue;
    const raw = body.mode === 'raw';
    if (!raw) {
      rivers.add(body.id);
      const bank = lowestBank(order, from, to, labels, ground, width, height);
      let lowest = bank;
      if (lowest < 0) {
        lowest = Infinity;
        for (let k = from; k < to; k++) lowest = Math.min(lowest, ground[order[k]]);
      }
      for (let k = from; k < to; k++) value[order[k]] = lowest;
      if (bank >= 0) solveRiver(order, from, to, labels, ground, width, height, value);
    }
    const offset = raw ? 0 : body.offset / mapping.scale;
    for (let k = from; k < to; k++) {
      const p = order[k];
      const level = Math.max(0, Math.min(max, Math.round((raw ? rawLevels[p] : value[p]) + offset)));
      levels[p] = level;
      target[p] = level;
      if (level <= ground[p]) low[p] = 1;
    }
  }
  // Rivers made of nodes are not painted: their bands are filled where the ground is below the surface
  for (const body of bodies) {
    if (body.mode !== 'river') continue;
    const target = body.fluid === 'water' ? water : lava;
    const resolved = resolveRiver(body, (x, z) => toY(mapping, ground[Math.round(z) * width + Math.round(x)] ?? 0));
    const r = rasterizeRiver(body, resolved, width, height);
    for (let k = 0; k < r.index.length; k++) {
      const p = r.index[k];
      const v = Math.max(0, Math.min(max, Math.round((r.level[k] - mapping.baseY) / mapping.scale)));
      if (ground[p] >= v) continue;
      if (v > target[p]) target[p] = v;
      levels[p] = Math.max(levels[p], v);
    }
  }
  return {
    water,
    lava,
    low,
    levels,
    anchors: findAnchors(order, starts, labels, width),
    arrows: findArrows(labels, levels, rivers, mapping, width, height),
  };
}
