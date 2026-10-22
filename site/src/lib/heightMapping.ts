export type BitDepth = 8 | 16;

export interface HeightSpec {
  base_y?: number;
  scale?: number;
  min_y?: number;
  max_y?: number;
}

/** Linear mapping from a raster value to a world Y: y = baseY + value * scale (see HeightMapping.java). */
export interface HeightMapping {
  baseY: number;
  scale: number;
  bitDepth: BitDepth;
}

export function maxValue(bitDepth: BitDepth): number {
  return bitDepth === 16 ? 65535 : 255;
}

export function resolveHeightMapping(spec: HeightSpec | undefined, bitDepth: BitDepth): HeightMapping {
  const max = maxValue(bitDepth);
  if (spec?.min_y !== undefined && spec.max_y !== undefined) {
    return { baseY: spec.min_y, scale: (spec.max_y - spec.min_y) / max, bitDepth };
  }
  if (spec?.base_y !== undefined) {
    return { baseY: spec.base_y, scale: spec.scale ?? 1, bitDepth };
  }
  // Defaults: 8-bit is one value per block from Y=0; 16-bit maps the full range onto Y 0..255.
  return bitDepth === 16 ? { baseY: 0, scale: 255 / max, bitDepth } : { baseY: 0, scale: 1, bitDepth };
}

export function toY(m: HeightMapping, value: number): number {
  return m.baseY + value * m.scale;
}

export function toBlockY(m: HeightMapping, value: number): number {
  return Math.floor(toY(m, value));
}

/** Raster value closest to a world Y, clamped to the bit depth. */
export function fromY(m: HeightMapping, y: number): number {
  const v = Math.round((y - m.baseY) / m.scale);
  return Math.max(0, Math.min(maxValue(m.bitDepth), v));
}
