/// <reference lib="webworker" />
import { solveFluids, type FluidResult } from '../lib/fluids';
import type { HeightMapping } from '../lib/heightMapping';
import type { FluidBody, Project, Raster } from '../lib/project';
import { exportZip, importZip, type ImportResult } from '../lib/zipIO';

export interface SolveArgs {
  labels: Uint16Array;
  raw: Uint16Array;
  bodies: FluidBody[];
  heightmap: Raster;
  mapping: HeightMapping;
}

export type Request =
  | { id: number; kind: 'solve'; args: SolveArgs }
  | { id: number; kind: 'export'; project: Project }
  | { id: number; kind: 'import'; bytes: Uint8Array };

export type Response =
  | { id: number; ok: true; result: FluidResult | Uint8Array | ImportResult }
  | { id: number; ok: false; error: string };

const ctx = self as unknown as DedicatedWorkerGlobalScope;

ctx.onmessage = (e: MessageEvent<Request>) => {
  const req = e.data;
  try {
    if (req.kind === 'solve') {
      const { labels, raw, bodies, heightmap, mapping } = req.args;
      const r = solveFluids(labels, raw, bodies, heightmap, mapping);
      ctx.postMessage({ id: req.id, ok: true, result: r } satisfies Response, [r.water.buffer, r.lava.buffer, r.low.buffer, r.levels.buffer]);
    } else if (req.kind === 'export') {
      const zip = exportZip(req.project);
      ctx.postMessage({ id: req.id, ok: true, result: zip } satisfies Response, [zip.buffer]);
    } else {
      ctx.postMessage({ id: req.id, ok: true, result: importZip(req.bytes) } satisfies Response);
    }
  } catch (err) {
    ctx.postMessage({ id: req.id, ok: false, error: (err as Error).message } satisfies Response);
  }
};
