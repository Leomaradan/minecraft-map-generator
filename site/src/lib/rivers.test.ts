import { describe, expect, it } from 'vitest';
import { solveFluids } from './fluids';
import { resolveHeightMapping } from './heightMapping';
import type { FluidBody, RiverNode } from './project';
import { addNode, nodeType, rasterizeRiver, removeNode, resolveRiver, splitLink, toggleLink } from './rivers';

const river = (nodes: RiverNode[], edges: [number, number][]): FluidBody => ({ id: 1, name: 'River', fluid: 'water', mode: 'river', offset: 0, nodes, edges });
const flat = () => 50;

describe('resolveRiver', () => {
  it('interpolates radius and level linearly along a chain', () => {
    const b = river([{ id: 1, x: 0, z: 0, radius: 2, level: 80 }, { id: 2, x: 10, z: 0 }, { id: 3, x: 30, z: 0, radius: 6, level: 60 }], [[1, 2], [2, 3]]);
    const r = resolveRiver(b, flat);
    expect(r.radius[1]).toBeCloseTo(2 + (4 * 10) / 30, 2);
    expect(r.level[1]).toBeCloseTo(80 - (20 * 10) / 30, 2);
  });

  it('drops the surface at a waterfall, on the side of the lower levels', () => {
    const b = river([{ id: 1, x: 0, z: 0, level: 80 }, { id: 2, x: 10, z: 0, waterfall: true, drop: 6 }, { id: 3, x: 20, z: 0, level: 60 }], [[2, 3], [1, 2]]);
    const r = resolveRiver(b, flat);
    // Without the waterfall the middle would be 70: here the top is 73 and the bottom 67
    expect(r.level[1]).toBeCloseTo(73, 2);
    const below = r.edgeLevels[0];
    const above = r.edgeLevels[1];
    expect(below[0]).toBeCloseTo(67, 2);
    expect(above[1]).toBeCloseTo(73, 2);
  });

  it('keeps a set waterfall level as its top', () => {
    const b = river([{ id: 1, x: 0, z: 0 }, { id: 2, x: 10, z: 0, waterfall: true, level: 90 }, { id: 3, x: 20, z: 0, level: 70 }], [[1, 2], [2, 3]]);
    const r = resolveRiver(b, flat);
    expect(r.edgeLevels[1][0]).toBeCloseTo(86, 2);
    expect(r.edgeLevels[0][1]).toBeCloseTo(90, 2);
  });

  it('follows the ground when no node has a level', () => {
    const r = resolveRiver(river([{ id: 1, x: 0, z: 0 }, { id: 2, x: 5, z: 0 }], [[1, 2]]), (x) => 40 + x);
    expect(r.level).toEqual([41, 46]);
    expect(r.radius).toEqual([3, 3]);
  });
});

describe('river nodes', () => {
  it('types the nodes from their links', () => {
    let b = river([], []);
    const a = addNode(b, 0, 0, null);
    b = { ...b, ...a };
    const c = addNode(b, 10, 0, a.id);
    b = { ...b, ...c };
    expect(nodeType(b, a.id)).toBe('leaf');
    const mid = splitLink(b, 0, 5, 0);
    b = { ...b, ...mid };
    expect(nodeType(b, mid.id)).toBe('intermediate');
    const d = addNode(b, 5, 8, mid.id);
    b = { ...b, ...d };
    expect(nodeType(b, mid.id)).toBe('connector');
    b = { ...b, ...toggleLink(b, mid.id, d.id) };
    expect(nodeType(b, mid.id)).toBe('intermediate');
    // Removing an intermediate node links its neighbours
    b = { ...b, ...removeNode(b, mid.id) };
    expect(b.edges).toEqual([[a.id, c.id]]);
  });

  it('fills a band where the ground is below the surface', () => {
    const w = 30;
    const ground = { width: w, height: 11, bitDepth: 8 as const, data: new Uint16Array(w * 11).fill(50) };
    const b = river([{ id: 1, x: 5, z: 5, radius: 2, level: 60 }, { id: 2, x: 25, z: 5, radius: 2, level: 60 }], [[1, 2]]);
    const raster = rasterizeRiver(b, resolveRiver(b, flat), w, 11);
    expect(raster.index).toContain(5 * w + 15);
    expect(raster.index).not.toContain(0);
    ground.data[5 * w + 20] = 70;
    const r = solveFluids(new Uint16Array(w * 11), new Uint16Array(w * 11), [b], ground, resolveHeightMapping(undefined, 8));
    expect(r.water[5 * w + 15]).toBe(60);
    expect(r.water[5 * w + 20]).toBe(0);
    expect(r.water[0]).toBe(0);
  });
});
