import { describe, expect, it } from 'vitest';
import { solveFluids, spillLevel } from './fluids';
import { resolveHeightMapping } from './heightMapping';
import type { FluidBody, Raster } from './project';

function raster(w: number, h: number, f: (x: number, y: number) => number): Raster {
  const data = new Uint16Array(w * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) data[y * w + x] = f(x, y);
  return { width: w, height: h, bitDepth: 8, data };
}
const m8 = resolveHeightMapping(undefined, 8);
const body = (id: number, mode: FluidBody['mode'], extra: Partial<FluidBody> = {}): FluidBody => ({ id, name: `b${id}`, fluid: 'water', mode, offset: 0, ...extra });

describe('solveFluids', () => {
  it('fills a lake zone where the ground is below its surface', () => {
    const ground = raster(7, 5, (x, y) => (x >= 2 && x < 5 && y >= 1 && y < 4 ? 60 : 90));
    const labels = new Uint16Array(35).fill(1);
    const r = solveFluids(labels, new Uint16Array(35), [body(1, 'lake', { level: 70 })], ground, m8);
    expect(r.water[2 * 7 + 3]).toBe(70);
    expect(r.water[0]).toBe(0);
    expect(r.low.every((v) => v === 0)).toBe(true);
  });

  it('places nothing for a lake without a surface', () => {
    const ground = raster(3, 3, () => 50);
    const r = solveFluids(new Uint16Array(9).fill(1), new Uint16Array(9), [body(1, 'lake')], ground, m8);
    expect(r.water.every((v) => v === 0)).toBe(true);
  });

  it('finds the spill level of a zone', () => {
    const ground = raster(5, 5, (x, y) => (x === 0 && y === 2 ? 55 : x === 2 && y === 2 ? 40 : 80));
    expect(spillLevel(new Uint16Array(25).fill(1), 1, ground.data, 5)).toBe(55);
    expect(spillLevel(new Uint16Array(25), 1, ground.data, 5)).toBe(-1);
  });

  it('keeps adjacent bodies at their own levels (85 next to 86)', () => {
    const ground = raster(8, 3, (x) => (x < 4 ? 85 : 86));
    const labels = new Uint16Array(24);
    for (let y = 0; y < 3; y++) {
      labels[y * 8 + 3] = 1;
      labels[y * 8 + 4] = 2;
    }
    const r = solveFluids(labels, new Uint16Array(24), [body(1, 'flow'), body(2, 'flow')], ground, m8);
    expect(r.water[3]).toBe(85);
    expect(r.water[4]).toBe(86);
  });

  it('slopes a free flow between its banks', () => {
    const w = 24;
    // ground descends along x; the river runs along the middle row, banks are the rows above and below
    const ground = raster(w, 3, (x) => 100 - x);
    const labels = new Uint16Array(w * 3);
    for (let x = 0; x < w; x++) labels[w + x] = 1;
    const r = solveFluids(labels, new Uint16Array(w * 3), [body(1, 'flow')], ground, m8);
    for (let x = 1; x < w; x++) expect(r.water[w + x]).toBeLessThanOrEqual(r.water[w + x - 1]);
    expect(r.water[w]).toBeGreaterThan(r.water[w + w - 1]);
  });

  it('applies the offset of a free flow', () => {
    const ground = raster(5, 5, () => 50);
    const labels = new Uint16Array(25);
    labels[12] = 1;
    const r = solveFluids(labels, new Uint16Array(25), [body(1, 'flow', { offset: 3 })], ground, m8);
    expect(r.water[12]).toBe(53);
  });

  it('points the flow arrows downhill and numbers each body', () => {
    const w = 40;
    const ground = raster(w, 5, (x) => 100 - x);
    const labels = new Uint16Array(w * 5);
    for (let y = 1; y < 4; y++) for (let x = 0; x < w; x++) labels[y * w + x] = 1;
    const r = solveFluids(labels, new Uint16Array(w * 5), [body(1, 'flow')], ground, m8);
    expect(r.arrows.length).toBeGreaterThan(0);
    for (let i = 0; i < r.arrows.length; i += 4) expect(r.arrows[i + 2]).toBeGreaterThan(0.9);
    expect(Array.from(r.anchors.subarray(0, 1))).toEqual([1]);
    expect(r.anchors.length).toBe(3);
  });

  it('handles a full 1024x1024 map quickly', () => {
    const w = 1024;
    const ground = raster(w, w, (x, y) => 60 + ((x >> 6) + (y >> 6)) % 8);
    const labels = new Uint16Array(w * w);
    for (let y = 100; y < 900; y++) for (let x = 100; x < 900; x++) labels[y * w + x] = 1;
    const raw = new Uint16Array(w * w).fill(70);
    for (const mode of ['raw', 'lake', 'flow'] as const) {
      const start = performance.now();
      const r = solveFluids(labels, raw, [body(1, mode, { level: 70 })], ground, m8);
      expect(r.water[500 * w + 500]).toBeGreaterThan(0);
      expect(performance.now() - start).toBeLessThan(5000);
    }
  });
});
