import { describe, expect, it } from 'vitest';
import { solveFluids } from './fluids';
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
  it('fills a lake at the lowest bank', () => {
    const ground = raster(7, 5, (x, y) => (x >= 2 && x < 5 && y >= 1 && y < 4 ? 60 : x === 1 && y === 2 ? 70 : 90));
    const labels = new Uint16Array(35);
    for (let y = 1; y < 4; y++) for (let x = 2; x < 5; x++) labels[y * 7 + x] = 1;
    const r = solveFluids(labels, new Uint16Array(35), [body(1, 'lake')], ground, m8);
    expect(r.water[2 * 7 + 3]).toBe(70);
    expect(r.low[2 * 7 + 3]).toBe(0);
  });

  it('keeps adjacent bodies at their own levels (85 next to 86)', () => {
    const ground = raster(8, 3, (x) => (x < 4 ? 85 : 86));
    const labels = new Uint16Array(24);
    for (let y = 0; y < 3; y++) {
      labels[y * 8 + 3] = 1;
      labels[y * 8 + 4] = 2;
    }
    const r = solveFluids(labels, new Uint16Array(24), [body(1, 'lake'), body(2, 'lake')], ground, m8);
    expect(r.water[3]).toBe(85);
    expect(r.water[4]).toBe(86);
  });

  it('slopes a river between its banks', () => {
    const w = 24;
    // ground descends along x; the river runs along the middle row, banks are the rows above and below
    const ground = raster(w, 3, (x) => 100 - x);
    const labels = new Uint16Array(w * 3);
    for (let x = 0; x < w; x++) labels[w + x] = 1;
    const r = solveFluids(labels, new Uint16Array(w * 3), [body(1, 'river')], ground, m8);
    for (let x = 1; x < w; x++) expect(r.water[w + x]).toBeLessThanOrEqual(r.water[w + x - 1]);
    expect(r.water[w]).toBeGreaterThan(r.water[w + w - 1]);
  });

  it('applies the override and the offset', () => {
    const ground = raster(5, 5, () => 50);
    const labels = new Uint16Array(25);
    labels[12] = 1;
    const r = solveFluids(labels, new Uint16Array(25), [body(1, 'lake', { levelOverride: 60, offset: -2 })], ground, m8);
    expect(r.water[12]).toBe(58);
  });

  it('handles a full 1024x1024 map quickly', () => {
    const w = 1024;
    const ground = raster(w, w, (x, y) => 60 + ((x >> 6) + (y >> 6)) % 8);
    const labels = new Uint16Array(w * w);
    for (let y = 100; y < 900; y++) for (let x = 100; x < 900; x++) labels[y * w + x] = 1;
    const raw = new Uint16Array(w * w).fill(70);
    for (const mode of ['raw', 'lake', 'river'] as const) {
      const start = performance.now();
      const r = solveFluids(labels, raw, [body(1, mode)], ground, m8);
      expect(r.water[500 * w + 500]).toBeGreaterThan(0);
      expect(performance.now() - start).toBeLessThan(5000);
    }
  });
});
