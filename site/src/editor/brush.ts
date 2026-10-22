/** Pixel indexes of a filled disc of the given diameter centered on (cx, cy). */
export function disc(cx: number, cy: number, size: number, width: number, height: number): number[] {
  const r = size / 2;
  const out: number[] = [];
  const x0 = Math.floor(cx - r);
  const y0 = Math.floor(cy - r);
  for (let y = y0; y <= Math.ceil(cy + r); y++) {
    for (let x = x0; x <= Math.ceil(cx + r); x++) {
      if (x < 0 || y < 0 || x >= width || y >= height) continue;
      if (size <= 1 ? x === cx && y === cy : (x - cx) ** 2 + (y - cy) ** 2 <= r * r) out.push(y * width + x);
    }
  }
  return out;
}

/** Brush stamps along the segment between two points, so fast strokes leave no gaps. */
export function stroke(from: { x: number; z: number } | null, to: { x: number; z: number }, size: number, width: number, height: number): number[] {
  if (!from) return disc(to.x, to.z, size, width, height);
  const steps = Math.max(Math.abs(to.x - from.x), Math.abs(to.z - from.z), 1);
  const seen = new Set<number>();
  for (let i = 0; i <= steps; i++) {
    const x = Math.round(from.x + ((to.x - from.x) * i) / steps);
    const z = Math.round(from.z + ((to.z - from.z) * i) / steps);
    for (const p of disc(x, z, size, width, height)) seen.add(p);
  }
  return [...seen];
}

/** Flood fill from a pixel through the pixels accepted by `accept` (4-neighbour). */
export function floodFill(start: number, width: number, height: number, accept: (index: number) => boolean): number[] {
  if (!accept(start)) return [];
  const seen = new Uint8Array(width * height);
  const out: number[] = [];
  const stack = [start];
  seen[start] = 1;
  while (stack.length > 0) {
    const p = stack.pop()!;
    out.push(p);
    const x = p % width;
    const y = (p - x) / width;
    const next = [x > 0 ? p - 1 : -1, x < width - 1 ? p + 1 : -1, y > 0 ? p - width : -1, y < height - 1 ? p + width : -1];
    for (const q of next) {
      if (q >= 0 && !seen[q] && accept(q)) {
        seen[q] = 1;
        stack.push(q);
      }
    }
  }
  return out;
}
