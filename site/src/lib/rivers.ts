import type { FluidBody, RiverNode } from './project';

/** Leaf, intermediate and connector follow from the number of links; waterfall is set by the user. */
export type RiverNodeType = 'leaf' | 'intermediate' | 'connector' | 'waterfall';

/** Radius in map pixels of a river that has none set. */
export const DEFAULT_RADIUS = 3;
/** Height in blocks of a waterfall that has none set. */
export const DEFAULT_DROP = 4;

export interface ResolvedRiver {
  /** Per node (same order as `body.nodes`): radius in map pixels. */
  radius: number[];
  /** Per node: surface Y, the top for a waterfall. */
  level: number[];
  /** The valid links, as node ids. */
  links: [number, number][];
  /** Per link of `links`: surface Y at each end; a waterfall end gets its top or its bottom. */
  edgeLevels: [number, number][];
}

interface Link {
  to: number;
  edge: number;
  weight: number;
}

function graph(body: FluidBody) {
  const nodes = body.nodes ?? [];
  const index = new Map(nodes.map((n, i) => [n.id, i]));
  const links: Link[][] = nodes.map(() => []);
  const edges: [number, number][] = [];
  for (const [a, b] of body.edges ?? []) {
    const i = index.get(a);
    const j = index.get(b);
    if (i === undefined || j === undefined || i === j) continue;
    const e = edges.length;
    edges.push([i, j]);
    const weight = 1 / Math.max(1, Math.hypot(nodes[i].x - nodes[j].x, nodes[i].z - nodes[j].z));
    links[i].push({ to: j, edge: e, weight });
    links[j].push({ to: i, edge: e, weight });
  }
  return { nodes, links, edges };
}

/** `fromLinks`: the type given by the number of links only, even for a waterfall. */
export function nodeType(body: FluidBody, id: number, fromLinks = false): RiverNodeType {
  const node = body.nodes?.find((n) => n.id === id);
  if (node?.waterfall && !fromLinks) return 'waterfall';
  const degree = (body.edges ?? []).filter(([a, b]) => a !== b && (a === id || b === id)).length;
  return degree <= 1 ? 'leaf' : degree === 2 ? 'intermediate' : 'connector';
}

function components(links: Link[][]): number[][] {
  const seen = new Uint8Array(links.length);
  const out: number[][] = [];
  for (let s = 0; s < links.length; s++) {
    if (seen[s]) continue;
    const comp = [s];
    seen[s] = 1;
    for (let k = 0; k < comp.length; k++) {
      for (const l of links[comp[k]]) {
        if (!seen[l.to]) {
          seen[l.to] = 1;
          comp.push(l.to);
        }
      }
    }
    out.push(comp);
  }
  return out;
}

/**
 * Fills the unknown values with the weighted average of their neighbours (harmonic interpolation on
 * the graph, weights 1/length): linear along a chain between two known nodes. `shift(i, link)` is
 * added to the neighbour's value as seen from node i.
 */
function interpolate(values: number[], known: boolean[], links: Link[][], shift: (i: number, l: Link) => number): void {
  for (let iter = 0; iter < 2000; iter++) {
    let delta = 0;
    for (let i = 0; i < values.length; i++) {
      if (known[i] || links[i].length === 0) continue;
      let sum = 0;
      let weight = 0;
      for (const l of links[i]) {
        sum += l.weight * (values[l.to] + shift(i, l));
        weight += l.weight;
      }
      const next = sum / weight;
      delta = Math.max(delta, Math.abs(next - values[i]));
      values[i] = next;
    }
    if (delta < 1e-4) break;
  }
}

/** Radius and surface of every node and link end. `groundY` gives the ground under a node when its river has no level at all. */
export function resolveRiver(body: FluidBody, groundY: (x: number, z: number) => number): ResolvedRiver {
  const { nodes, links, edges } = graph(body);
  const comps = components(links);

  const radius = nodes.map((n) => n.radius ?? NaN);
  const radiusKnown = nodes.map((n) => n.radius !== undefined);
  for (const comp of comps) {
    const known = comp.filter((i) => radiusKnown[i]);
    const mean = known.length > 0 ? known.reduce((s, i) => s + radius[i], 0) / known.length : DEFAULT_RADIUS;
    for (const i of comp) if (!radiusKnown[i]) radius[i] = mean;
    if (known.length === 0) for (const i of comp) radiusKnown[i] = true;
  }
  interpolate(radius, radiusKnown, links, () => 0);

  // Which links of a waterfall are below it: the side of the lower known levels.
  const drop = nodes.map((n) => (n.waterfall ? (n.drop ?? DEFAULT_DROP) : 0));
  const below = new Set<string>(); // `${node},${edge}`
  const nearestKnown = (from: number, start: number): number | undefined => {
    const seen = new Set([from, start]);
    const queue = [start];
    for (let k = 0; k < queue.length; k++) {
      const n = nodes[queue[k]];
      if (n.level !== undefined) return n.level - (n.waterfall ? drop[queue[k]] / 2 : 0);
      for (const l of links[queue[k]]) if (!seen.has(l.to)) { seen.add(l.to); queue.push(l.to); }
    }
    return undefined;
  };
  nodes.forEach((n, i) => {
    if (!n.waterfall || links[i].length === 0) return;
    const reach = links[i].map((l) => nearestKnown(i, l.to));
    let top: number;
    if (n.level !== undefined) {
      const middle = n.level - drop[i] / 2;
      top = reach.findIndex((v) => v !== undefined && v > middle);
      if (top < 0) top = Math.max(0, reach.findIndex((v) => v === undefined));
    } else {
      top = 0;
      reach.forEach((v, k) => { if (v !== undefined && (reach[top] === undefined || v > reach[top]!)) top = k; });
    }
    links[i].forEach((l, k) => { if (k !== top) below.add(`${i},${l.edge}`); });
  });
  const lowered = (i: number, edge: number) => (below.has(`${i},${edge}`) ? drop[i] : 0);

  const level = nodes.map((n) => n.level ?? NaN);
  const levelKnown = nodes.map((n) => n.level !== undefined);
  for (const comp of comps) {
    const known = comp.filter((i) => levelKnown[i]);
    if (known.length === 0) {
      // No level anywhere: one block above the ground at each node, waterfalls in between
      for (const i of comp) {
        level[i] = groundY(nodes[i].x, nodes[i].z) + 1;
        levelKnown[i] = !nodes[i].waterfall;
      }
    } else {
      const mean = known.reduce((s, i) => s + level[i], 0) / known.length;
      for (const i of comp) if (!levelKnown[i]) level[i] = mean;
    }
  }
  // Node i sees the end of its neighbour lowered by the neighbour's drop, and raises it by its own drop.
  interpolate(level, levelKnown, links, (i, l) => lowered(i, l.edge) - lowered(l.to, l.edge));

  const edgeLevels = edges.map(([i, j], e): [number, number] => [level[i] - lowered(i, e), level[j] - lowered(j, e)]);
  return { radius, level, links: edges.map(([i, j]) => [nodes[i].id, nodes[j].id]), edgeLevels };
}

export interface RiverRaster {
  /** Pixel indexes covered by the river. */
  index: Int32Array;
  /** Surface Y of each covered pixel. */
  level: Float32Array;
}

/**
 * The pixels of the river: each link is a band whose radius and surface go linearly from one end to
 * the other, with round ends. Where bands overlap, the pixel takes the band it is most inside of.
 */
export function rasterizeRiver(body: FluidBody, r: ResolvedRiver, width: number, height: number): RiverRaster {
  const { nodes, links, edges } = graph(body);
  const items: { a: number; b: number; la: number; lb: number }[] = edges.map(([a, b], e) => ({ a, b, la: r.edgeLevels[e][0], lb: r.edgeLevels[e][1] }));
  nodes.forEach((_, i) => { if (links[i].length === 0) items.push({ a: i, b: i, la: r.level[i], lb: r.level[i] }); });
  if (items.length === 0) return { index: new Int32Array(0), level: new Float32Array(0) };

  const reach = (i: number) => Math.max(0.5, r.radius[i]);
  let x0 = Infinity, z0 = Infinity, x1 = -Infinity, z1 = -Infinity;
  nodes.forEach((n, i) => {
    x0 = Math.min(x0, n.x - reach(i)); x1 = Math.max(x1, n.x + reach(i));
    z0 = Math.min(z0, n.z - reach(i)); z1 = Math.max(z1, n.z + reach(i));
  });
  x0 = Math.max(0, Math.floor(x0)); z0 = Math.max(0, Math.floor(z0));
  x1 = Math.min(width - 1, Math.ceil(x1)); z1 = Math.min(height - 1, Math.ceil(z1));
  if (x1 < x0 || z1 < z0) return { index: new Int32Array(0), level: new Float32Array(0) };
  const bw = x1 - x0 + 1;
  const score = new Float32Array(bw * (z1 - z0 + 1)).fill(Infinity);
  const value = new Float32Array(score.length);

  for (const { a, b, la, lb } of items) {
    const A = nodes[a];
    const B = nodes[b];
    const ra = reach(a);
    const rb = reach(b);
    const dx = B.x - A.x;
    const dz = B.z - A.z;
    const len2 = dx * dx + dz * dz;
    const pad = Math.max(ra, rb);
    for (let z = Math.max(z0, Math.floor(Math.min(A.z, B.z) - pad)); z <= Math.min(z1, Math.ceil(Math.max(A.z, B.z) + pad)); z++) {
      for (let x = Math.max(x0, Math.floor(Math.min(A.x, B.x) - pad)); x <= Math.min(x1, Math.ceil(Math.max(A.x, B.x) + pad)); x++) {
        const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((x - A.x) * dx + (z - A.z) * dz) / len2));
        const d = Math.hypot(x - (A.x + t * dx), z - (A.z + t * dz));
        const rad = ra + (rb - ra) * t;
        if (d > rad) continue;
        const k = (z - z0) * bw + (x - x0);
        const s = d / rad;
        if (s < score[k]) {
          score[k] = s;
          value[k] = la + (lb - la) * t;
        }
      }
    }
  }
  const index: number[] = [];
  const level: number[] = [];
  for (let k = 0; k < score.length; k++) {
    if (score[k] === Infinity) continue;
    index.push((z0 + Math.floor(k / bw)) * width + x0 + (k % bw));
    level.push(value[k]);
  }
  return { index: Int32Array.from(index), level: Float32Array.from(level) };
}

// ---- editing: each returns the new nodes and edges -------------------------------------------

type Graph = Pick<FluidBody, 'nodes' | 'edges'>;

function nextNodeId(body: FluidBody): number {
  return (body.nodes ?? []).reduce((m, n) => Math.max(m, n.id), 0) + 1;
}

/** Adds a node, linked to `linkTo` when given. */
export function addNode(body: FluidBody, x: number, z: number, linkTo: number | null): Graph & { id: number } {
  const id = nextNodeId(body);
  const edges = [...(body.edges ?? [])];
  if (linkTo !== null && body.nodes?.some((n) => n.id === linkTo)) edges.push([linkTo, id]);
  return { id, nodes: [...(body.nodes ?? []), { id, x, z }], edges };
}

/** Links two nodes, or unlinks them when they already are. */
export function toggleLink(body: FluidBody, a: number, b: number): Graph {
  const edges = body.edges ?? [];
  if (a === b) return { nodes: body.nodes, edges };
  const has = edges.some(([u, v]) => (u === a && v === b) || (u === b && v === a));
  return { nodes: body.nodes, edges: has ? edges.filter(([u, v]) => !((u === a && v === b) || (u === b && v === a))) : [...edges, [a, b]] };
}

/** Removes a node; the two neighbours of an intermediate node are linked to each other. */
export function removeNode(body: FluidBody, id: number): Graph {
  const edges = body.edges ?? [];
  const around = edges.filter(([u, v]) => u === id || v === id).map(([u, v]) => (u === id ? v : u));
  const kept = edges.filter(([u, v]) => u !== id && v !== id);
  if (around.length === 2 && around[0] !== around[1]) kept.push([around[0], around[1]]);
  return { nodes: (body.nodes ?? []).filter((n) => n.id !== id), edges: kept };
}

/** Inserts a node in the middle of a link. */
export function splitLink(body: FluidBody, edge: number, x: number, z: number): Graph & { id: number } {
  const id = nextNodeId(body);
  const edges = [...(body.edges ?? [])];
  const [a, b] = edges[edge];
  edges.splice(edge, 1, [a, id], [id, b]);
  return { id, nodes: [...(body.nodes ?? []), { id, x, z }], edges };
}

export function updateNode(body: FluidBody, id: number, patch: Partial<RiverNode>): Graph {
  return { nodes: (body.nodes ?? []).map((n) => (n.id === id ? { ...n, ...patch } : n)), edges: body.edges };
}

// ---- hit tests ------------------------------------------------------------------------------

export function nodeAt(body: FluidBody, x: number, z: number, tolerance: number): RiverNode | null {
  let best: RiverNode | null = null;
  let bestD = tolerance;
  for (const n of body.nodes ?? []) {
    const d = Math.hypot(n.x - x, n.z - z);
    if (d <= bestD) {
      best = n;
      bestD = d;
    }
  }
  return best;
}

/** The link closest to a point: its index, the distance to its center line and the river radius there. */
export function linkAt(body: FluidBody, r: ResolvedRiver, x: number, z: number): { edge: number; distance: number; radius: number } | null {
  const nodes = body.nodes ?? [];
  const index = new Map(nodes.map((n, i) => [n.id, i]));
  let best: { edge: number; distance: number; radius: number } | null = null;
  (body.edges ?? []).forEach(([u, v], e) => {
    const i = index.get(u);
    const j = index.get(v);
    if (i === undefined || j === undefined) return;
    const A = nodes[i];
    const B = nodes[j];
    const dx = B.x - A.x;
    const dz = B.z - A.z;
    const len2 = dx * dx + dz * dz;
    const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((x - A.x) * dx + (z - A.z) * dz) / len2));
    const distance = Math.hypot(x - (A.x + t * dx), z - (A.z + t * dz));
    if (!best || distance < best.distance) best = { edge: e, distance, radius: r.radius[i] + (r.radius[j] - r.radius[i]) * t };
  });
  return best;
}
