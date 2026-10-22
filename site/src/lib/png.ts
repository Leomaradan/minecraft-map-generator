import { decode, encode } from 'fast-png';
import type { BitDepth } from './heightMapping';
import type { Raster } from './project';

export interface DecodedMap {
  raster: Raster;
  warnings: string[];
}

/** Decodes a heightmap-like PNG: grayscale 8 or 16 bit. Color images use the red channel (warning). */
export function decodeGrayPng(bytes: Uint8Array, label = 'image'): DecodedMap {
  let img;
  try {
    img = decode(bytes);
  } catch (e) {
    throw new Error(`${label} is not a valid PNG: ${(e as Error).message}`);
  }
  const warnings: string[] = [];
  if (img.depth !== 8 && img.depth !== 16) {
    throw new Error(`${label} must be 8-bit or 16-bit (it is ${img.depth}-bit)`);
  }
  const channels = img.channels;
  const n = img.width * img.height;
  const out = new Uint16Array(n);
  const data = img.data as Uint8Array | Uint16Array;
  if (img.palette) {
    const pal = img.palette as number[][];
    for (let i = 0; i < n; i++) out[i] = pal[data[i]][0];
    warnings.push(`${label} is an indexed color image; the red channel is used`);
    return { raster: { width: img.width, height: img.height, bitDepth: 8, data: out }, warnings };
  }
  if (channels >= 3) warnings.push(`${label} is a color image; the red channel is used`);
  for (let i = 0; i < n; i++) out[i] = data[i * channels];
  return { raster: { width: img.width, height: img.height, bitDepth: img.depth as BitDepth, data: out }, warnings };
}

export function encodeGrayPng(r: Raster): Uint8Array {
  if (r.bitDepth === 8) {
    return encode({ width: r.width, height: r.height, data: Uint8Array.from(r.data), depth: 8, channels: 1 });
  }
  return encode({ width: r.width, height: r.height, data: r.data, depth: 16, channels: 1 });
}

export function encodeRgbPng(width: number, height: number, rgb: Uint8Array): Uint8Array {
  return encode({ width, height, data: rgb, depth: 8, channels: 3 });
}

export function decodeRgbPng(bytes: Uint8Array, label = 'image'): { width: number; height: number; rgb: Uint8Array } {
  const img = decode(bytes);
  if (img.depth !== 8) throw new Error(`${label} must be 8-bit per channel`);
  const n = img.width * img.height;
  const rgb = new Uint8Array(n * 3);
  const data = img.data as Uint8Array;
  const pal = img.palette as number[][] | undefined;
  for (let i = 0; i < n; i++) {
    if (pal) {
      const c = pal[data[i]];
      rgb.set([c[0], c[1], c[2]], i * 3);
    } else if (img.channels >= 3) {
      rgb.set([data[i * img.channels], data[i * img.channels + 1], data[i * img.channels + 2]], i * 3);
    } else {
      const g = data[i * img.channels];
      rgb.set([g, g, g], i * 3);
    }
  }
  return { width: img.width, height: img.height, rgb };
}
