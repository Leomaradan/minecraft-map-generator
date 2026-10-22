import type { FluidResult } from '../lib/fluids';
import type { FluidBody, Project, Raster } from '../lib/project';
import { resolveHeightMapping } from '../lib/heightMapping';
import type { ImportResult } from '../lib/zipIO';
import type { Request, Response, SolveArgs } from './worker';

let worker: Worker | null = null;
let nextId = 1;
const pending = new Map<number, { resolve: (v: never) => void; reject: (e: Error) => void }>();

function getWorker(): Worker {
  if (!worker) {
    worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = (e: MessageEvent<Response>) => {
      const p = pending.get(e.data.id);
      if (!p) return;
      pending.delete(e.data.id);
      if (e.data.ok) p.resolve(e.data.result as never);
      else p.reject(new Error(e.data.error));
    };
  }
  return worker;
}

function call<T>(build: (id: number) => Request): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve: resolve as (v: never) => void, reject });
    getWorker().postMessage(build(id));
  });
}

export function importZipAsync(bytes: Uint8Array): Promise<ImportResult> {
  return call((id) => ({ id, kind: 'import', bytes }));
}

export function exportZipAsync(project: Project): Promise<Uint8Array> {
  return call((id) => ({ id, kind: 'export', project }));
}

// ---- fluids: one solve at a time, the newest request replaces the queued one -------------------

interface Waiter {
  resolve: (r: FluidResult) => void;
  reject: (e: Error) => void;
}

let running = false;
let queued: { args: SolveArgs; key: unknown[]; waiters: Waiter[] } | null = null;
let cache: { key: unknown[]; result: FluidResult } | null = null;

function sameKey(a: unknown[], b: unknown[]): boolean {
  return a.every((v, i) => v === b[i]);
}

async function pump(): Promise<void> {
  if (running || !queued) return;
  const job = queued;
  queued = null;
  running = true;
  try {
    const result = await call<FluidResult>((id) => ({ id, kind: 'solve', args: job.args }));
    cache = { key: job.key, result };
    job.waiters.forEach((w) => w.resolve(result));
  } catch (e) {
    job.waiters.forEach((w) => w.reject(e as Error));
  } finally {
    running = false;
    void pump();
  }
}

/**
 * Water and lava rasters for the painted bodies, computed in the worker. `rev` counts in-place raster
 * edits. While a solve is running, only the newest request is kept, and all callers get its result.
 */
export function solveAsync(labels: Uint16Array, raw: Uint16Array, bodies: FluidBody[], heightmap: Raster, heightConfig: unknown, rev: number): Promise<FluidResult> {
  const mapping = resolveHeightMapping(heightConfig as never, heightmap.bitDepth);
  const key = [labels, raw, bodies, heightmap, mapping.baseY, mapping.scale, mapping.bitDepth, rev];
  if (cache && sameKey(cache.key, key)) return Promise.resolve(cache.result);
  return new Promise<FluidResult>((resolve, reject) => {
    const waiters = [...(queued?.waiters ?? []), { resolve, reject }];
    // Labels are edited in place on the main thread: the worker gets a snapshot.
    queued = { key, waiters, args: { labels: labels.slice(), raw, bodies, heightmap, mapping } };
    void pump();
  });
}
