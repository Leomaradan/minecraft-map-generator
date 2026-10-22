import { describe, expect, it } from 'vitest';
import { fromY, resolveHeightMapping, toBlockY, toY } from './heightMapping';

describe('heightMapping', () => {
  it('defaults', () => {
    expect(toY(resolveHeightMapping(undefined, 8), 64)).toBe(64);
    expect(toY(resolveHeightMapping(undefined, 16), 65535)).toBeCloseTo(255);
  });
  it('base_y/scale and range', () => {
    const a = resolveHeightMapping({ base_y: -64, scale: 2 }, 8);
    expect(toBlockY(a, 10)).toBe(-44);
    const b = resolveHeightMapping({ min_y: 0, max_y: 700 }, 16);
    expect(toY(b, 65535)).toBeCloseTo(700);
    expect(fromY(b, 350)).toBe(32768);
  });
});
