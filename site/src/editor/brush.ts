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

/** Pixel indexes inside a closed polygon (even-odd rule, pixel centers), plus the pixels of its outline. */
export function polygon(points: { x: number; z: number }[], width: number, height: number): number[] {
  if (points.length === 0) return [];
  const seen = new Set<number>();
  for (let i = 0; i < points.length; i++) {
    for (const p of stroke(points[i], points[(i + 1) % points.length], 1, width, height)) seen.add(p);
  }
  const zs = points.map((p) => p.z);
  const z0 = Math.max(0, Math.min(...zs));
  const z1 = Math.min(height - 1, Math.max(...zs));
  for (let z = z0; z <= z1; z++) {
    const cy = z + 0.5;
    const xs: number[] = [];
    for (let i = 0; i < points.length; i++) {
      const a = points[i];
      const b = points[(i + 1) % points.length];
      const ay = a.z + 0.5;
      const by = b.z + 0.5;
      if ((ay <= cy) === (by <= cy)) continue;
      xs.push(a.x + 0.5 + ((cy - ay) / (by - ay)) * (b.x - a.x));
    }
    xs.sort((u, v) => u - v);
    for (let i = 0; i + 1 < xs.length; i += 2) {
      const from = Math.max(0, Math.ceil(xs[i] - 0.5));
      const to = Math.min(width - 1, Math.floor(xs[i + 1] - 0.5));
      for (let x = from; x <= to; x++) seen.add(z * width + x);
    }
  }
  return [...seen];
}
