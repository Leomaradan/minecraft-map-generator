export interface Rgb {
  r: number;
  g: number;
  b: number;
}

/**
 * Average color of a BLP2 texture, weighted by alpha, read from a small mip level
 * (about 8x8). Supports DXT1/3/5, palettized and uncompressed textures.
 * Returns undefined for other formats or fully transparent textures.
 */
export function blpAverageColor(data: Buffer): Rgb | undefined {
  if (data.toString('latin1', 0, 4) !== 'BLP2') return undefined;
  const compression = data[8];
  const alphaDepth = data[9];
  const alphaType = data[10];
  const width = data.readUInt32LE(12);
  const height = data.readUInt32LE(16);
  // The level whose smaller side is about 8 pixels, if present.
  let level = Math.max(0, Math.floor(Math.log2(Math.min(width, height))) - 3);
  while (level > 0 && (data.readUInt32LE(20 + level * 4) === 0 || data.readUInt32LE(84 + level * 4) === 0)) level--;
  const offset = data.readUInt32LE(20 + level * 4);
  const size = data.readUInt32LE(84 + level * 4);
  if (!offset || !size || offset + size > data.length) return undefined;
  const w = Math.max(1, width >> level);
  const h = Math.max(1, height >> level);
  const pixels = decode(data, offset, w, h, compression, alphaDepth, alphaType);
  if (!pixels) return undefined;

  let r = 0;
  let g = 0;
  let b = 0;
  let total = 0;
  for (let i = 0; i < pixels.length; i += 4) {
    const a = pixels[i + 3] / 255;
    r += pixels[i] * a;
    g += pixels[i + 1] * a;
    b += pixels[i + 2] * a;
    total += a;
  }
  return total > 0 ? { r: r / total, g: g / total, b: b / total } : undefined;
}

/** RGBA pixels of one mip level. */
function decode(data: Buffer, offset: number, w: number, h: number, compression: number, alphaDepth: number, alphaType: number): Uint8Array | undefined {
  const out = new Uint8Array(w * h * 4);
  if (compression === 2) {
    const blockSize = alphaType === 0 ? 8 : 16;
    const blocksX = Math.ceil(w / 4);
    for (let by = 0; by < Math.ceil(h / 4); by++) {
      for (let bx = 0; bx < blocksX; bx++) {
        const block = offset + (by * blocksX + bx) * blockSize;
        if (block + blockSize > data.length) return undefined;
        const alphas = alphaType === 7 ? dxt5Alpha(data, block) : alphaType === 1 ? dxt3Alpha(data, block) : undefined;
        const colors = dxtColors(data, alphaType === 0 ? block : block + 8, alphaType === 0 && alphaDepth > 0);
        for (let p = 0; p < 16; p++) {
          const x = bx * 4 + (p % 4);
          const y = by * 4 + Math.floor(p / 4);
          if (x >= w || y >= h) continue;
          const o = (y * w + x) * 4;
          out.set(colors[p], o);
          if (alphas) out[o + 3] = alphas[p];
        }
      }
    }
    return out;
  }
  if (compression === 1) {
    // 8-bit palette indices, then alpha (alphaDepth bits per pixel).
    const count = w * h;
    for (let i = 0; i < count; i++) {
      const color = data.readUInt32LE(148 + data[offset + i] * 4); // BGRA
      out[i * 4] = (color >> 16) & 0xff;
      out[i * 4 + 1] = (color >> 8) & 0xff;
      out[i * 4 + 2] = color & 0xff;
      out[i * 4 + 3] = paletteAlpha(data, offset + count, i, alphaDepth);
    }
    return out;
  }
  if (compression === 3) {
    for (let i = 0; i < w * h; i++) {
      const o = offset + i * 4; // BGRA
      out[i * 4] = data[o + 2];
      out[i * 4 + 1] = data[o + 1];
      out[i * 4 + 2] = data[o];
      out[i * 4 + 3] = data[o + 3];
    }
    return out;
  }
  return undefined;
}

function paletteAlpha(data: Buffer, start: number, i: number, depth: number): number {
  if (depth === 8) return data[start + i];
  if (depth === 4) return ((data[start + (i >> 1)] >> ((i & 1) * 4)) & 0x0f) * 17;
  if (depth === 1) return (data[start + (i >> 3)] >> (i & 7)) & 1 ? 255 : 0;
  return 255;
}

function rgb565(value: number): [number, number, number] {
  return [((value >> 11) & 0x1f) * 255 / 31, ((value >> 5) & 0x3f) * 255 / 63, (value & 0x1f) * 255 / 31];
}

/** The 16 RGBA colors of a DXT color block. */
function dxtColors(data: Buffer, block: number, punchThrough: boolean): number[][] {
  const c0 = data.readUInt16LE(block);
  const c1 = data.readUInt16LE(block + 2);
  const [a, b] = [rgb565(c0), rgb565(c1)];
  const mix = (t: number) => a.map((v, i) => Math.round(v * (1 - t) + b[i] * t));
  const palette =
    c0 > c1 || !punchThrough
      ? [[...a.map(Math.round), 255], [...b.map(Math.round), 255], [...mix(1 / 3), 255], [...mix(2 / 3), 255]]
      : [[...a.map(Math.round), 255], [...b.map(Math.round), 255], [...mix(1 / 2), 255], [0, 0, 0, 0]];
  const bits = data.readUInt32LE(block + 4);
  const colors: number[][] = [];
  for (let p = 0; p < 16; p++) colors.push(palette[(bits >>> (p * 2)) & 3]);
  return colors;
}

function dxt3Alpha(data: Buffer, block: number): number[] {
  const alphas: number[] = [];
  for (let p = 0; p < 16; p++) alphas.push(((data[block + (p >> 1)] >> ((p & 1) * 4)) & 0x0f) * 17);
  return alphas;
}

function dxt5Alpha(data: Buffer, block: number): number[] {
  const a0 = data[block];
  const a1 = data[block + 1];
  const table = [a0, a1];
  if (a0 > a1) for (let i = 1; i < 7; i++) table.push(Math.round(((7 - i) * a0 + i * a1) / 7));
  else {
    for (let i = 1; i < 5; i++) table.push(Math.round(((5 - i) * a0 + i * a1) / 5));
    table.push(0, 255);
  }
  let bits = 0n;
  for (let i = 0; i < 6; i++) bits |= BigInt(data[block + 2 + i]) << BigInt(i * 8);
  const alphas: number[] = [];
  for (let p = 0; p < 16; p++) alphas.push(table[Number((bits >> BigInt(p * 3)) & 7n)]);
  return alphas;
}
