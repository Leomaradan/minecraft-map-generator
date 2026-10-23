import { create } from 'zustand';
import { decodeGrayPng } from '../lib/png';
import { emptyProject, type BiomeEntry, type ReferenceImage, type FluidBody, type Json, type JsonObject, type Project, type StructureInstance, type StructureTemplate } from '../lib/project';
import { readNbtSize } from '../lib/nbt';
import { rgbToHex } from '../lib/zipIO';
import { exportZipAsync, importZipAsync } from './workerClient';

export type Section = 'general' | 'heightmap' | 'biomes' | 'structures';

interface Patch {
  field: 'fluidLabels' | 'biomeIndex';
  indexes: number[];
  before: number[];
  after: number[];
}

interface State {
  project: Project;
  section: Section;
  /** Bumped whenever a raster is modified in place, to redraw overlays. */
  rev: number;
  messages: { level: 'error' | 'warning'; text: string }[];
  undo: Patch[][];
  redo: Patch[][];
  setSection: (s: Section) => void;
  updateProject: (fn: (p: Project) => void) => void;
  setConfig: (key: string, value: Json | undefined) => void;
  loadHeightmap: (bytes: Uint8Array) => string[];
  addReference: (name: string, bytes: Uint8Array, mime: string) => void;
  updateReference: (id: number, patch: Partial<Pick<ReferenceImage, 'name' | 'opacity' | 'visible'>>) => void;
  removeReference: (id: number) => void;
  setAllReferencesVisible: (visible: boolean) => void;
  /** Asks the map to center on a pixel; `n` changes with each request. */
  focus: { x: number; z: number; n: number } | null;
  focusOn: (x: number, z: number) => void;
  paint: (field: Patch['field'], indexes: number[], value: number) => void;
  commitStroke: () => void;
  doUndo: () => void;
  doRedo: () => void;
  addBody: (fluid: FluidBody['fluid'], mode: 'lake' | 'flow' | 'river') => FluidBody;
  updateBody: (id: number, patch: Partial<FluidBody>) => void;
  removeBody: (id: number) => void;
  addBiome: () => BiomeEntry;
  updateBiome: (id: number, fn: (data: JsonObject) => JsonObject, color?: string) => void;
  removeBiome: (id: number) => void;
  addTemplate: (t: Omit<StructureTemplate, 'id'>) => StructureTemplate;
  addNbt: (name: string, bytes: Uint8Array) => StructureTemplate;
  updateTemplate: (id: number, patch: Partial<StructureTemplate>) => void;
  removeTemplate: (id: number) => void;
  addInstance: (templateId: number, x: number, z: number) => number;
  updateInstance: (id: number, patch: Partial<StructureInstance>) => void;
  removeInstance: (id: number) => void;
  importZipBytes: (bytes: Uint8Array) => Promise<void>;
  exportZipBytes: () => Promise<Uint8Array>;
  reset: () => void;
}

let stroke: Patch[] = [];

function hslToHex(h: number, s: number, l: number): string {
  const a = s * Math.min(l, 1 - l);
  const f = (n: number) => {
    const k = (n + h / 30) % 12;
    return l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1));
  };
  return rgbToHex(Math.round(f(0) * 255), Math.round(f(8) * 255), Math.round(f(4) * 255));
}

export function nextBiomeColor(used: string[]): string {
  for (let i = 0; i < 360; i++) {
    const c = hslToHex((i * 137.508) % 360, 0.65, 0.5);
    if (!used.includes(c)) return c;
  }
  return '#808080';
}

function mutated(p: Project): Project {
  return { ...p };
}

export const useStore = create<State>((set, get) => ({
  project: emptyProject(),
  section: 'general',
  rev: 0,
  messages: [],
  undo: [],
  redo: [],
  setSection: (section) => set({ section }),
  updateProject: (fn) => {
    const p = mutated(get().project);
    fn(p);
    set({ project: p });
  },
  setConfig: (key, value) =>
    get().updateProject((p) => {
      const c = { ...p.config };
      if (value === undefined) delete c[key];
      else c[key] = value;
      p.config = c;
    }),
  loadHeightmap: (bytes) => {
    const { raster, warnings } = decodeGrayPng(bytes, 'heightmap');
    const old = get().project.heightmap;
    get().updateProject((p) => {
      p.heightmap = raster;
      p.heightmapBytes = bytes;
      if (!old || old.width !== raster.width || old.height !== raster.height) {
        const n = raster.width * raster.height;
        p.fluidLabels = new Uint16Array(n);
        p.fluidRaw = new Uint16Array(n);
        p.biomeIndex = new Uint16Array(n);
        p.bodies = [];
        p.instances = [];
      }
    });
    set({ undo: [], redo: [], rev: get().rev + 1 });
    return warnings;
  },
  addReference: (name, bytes, mime) =>
    get().updateProject((p) => {
      p.references = [...p.references, { id: p.nextId++, name, bytes, mime, opacity: 0.5, visible: true }];
    }),
  updateReference: (id, patch) => get().updateProject((p) => { p.references = p.references.map((r) => (r.id === id ? { ...r, ...patch } : r)); }),
  removeReference: (id) => get().updateProject((p) => { p.references = p.references.filter((r) => r.id !== id); }),
  setAllReferencesVisible: (visible) => get().updateProject((p) => { p.references = p.references.map((r) => ({ ...r, visible })); }),
  focus: null,
  focusOn: (x, z) => set({ focus: { x, z, n: (get().focus?.n ?? 0) + 1 } }),
  paint: (field, indexes, value) => {
    const arr = get().project[field];
    const patch: Patch = { field, indexes: [], before: [], after: [] };
    for (const i of indexes) {
      if (arr[i] === value) continue;
      patch.indexes.push(i);
      patch.before.push(arr[i]);
      patch.after.push(value);
      arr[i] = value;
    }
    if (patch.indexes.length > 0) {
      stroke.push(patch);
      set({ rev: get().rev + 1 });
    }
  },
  commitStroke: () => {
    if (stroke.length === 0) return;
    const done = stroke;
    stroke = [];
    set({ undo: [...get().undo.slice(-49), done], redo: [] });
  },
  doUndo: () => {
    const { undo, redo, project } = get();
    const last = undo[undo.length - 1];
    if (!last) return;
    for (const p of last) p.indexes.forEach((idx, i) => { project[p.field][idx] = p.before[i]; });
    set({ undo: undo.slice(0, -1), redo: [...redo, last], rev: get().rev + 1 });
  },
  doRedo: () => {
    const { undo, redo, project } = get();
    const last = redo[redo.length - 1];
    if (!last) return;
    for (const p of last) p.indexes.forEach((idx, i) => { project[p.field][idx] = p.after[i]; });
    set({ redo: redo.slice(0, -1), undo: [...undo, last], rev: get().rev + 1 });
  },
  addBody: (fluid, mode) => {
    const p = get().project;
    const names = { lake: ['Lake', 'Lava lake'], flow: ['Free water flow', 'Free lava flow'], river: ['River', 'Lava river'] };
    const body: FluidBody = { id: p.nextId, name: names[mode][fluid === 'water' ? 0 : 1], fluid, mode, offset: 0 };
    if (mode === 'river') {
      body.nodes = [];
      body.edges = [];
    }
    get().updateProject((q) => { q.bodies = [...q.bodies, body]; q.nextId++; });
    return body;
  },
  updateBody: (id, patch) => get().updateProject((p) => { p.bodies = p.bodies.map((b) => (b.id === id ? { ...b, ...patch } : b)); }),
  removeBody: (id) => {
    const labels = get().project.fluidLabels;
    for (let i = 0; i < labels.length; i++) if (labels[i] === id) labels[i] = 0;
    get().updateProject((p) => { p.bodies = p.bodies.filter((b) => b.id !== id); });
    set({ rev: get().rev + 1 });
  },
  addBiome: () => {
    const p = get().project;
    const entry: BiomeEntry = { id: p.nextId, color: nextBiomeColor(p.biomes.map((b) => b.color)), data: { biome: 'minecraft:plains' } };
    get().updateProject((q) => {
      q.biomes = [...q.biomes, entry];
      q.nextId++;
      if (q.defaultBiomeId === null) q.defaultBiomeId = entry.id;
    });
    return entry;
  },
  updateBiome: (id, fn, color) => get().updateProject((p) => {
    p.biomes = p.biomes.map((b) => (b.id === id ? { ...b, data: fn(b.data), color: color ?? b.color } : b));
  }),
  removeBiome: (id) => {
    const idx = get().project.biomeIndex;
    for (let i = 0; i < idx.length; i++) if (idx[i] === id) idx[i] = 0;
    get().updateProject((p) => {
      p.biomes = p.biomes.filter((b) => b.id !== id);
      if (p.defaultBiomeId === id) p.defaultBiomeId = p.biomes[0]?.id ?? null;
    });
    set({ rev: get().rev + 1 });
  },
  addTemplate: (t) => {
    const template: StructureTemplate = { ...t, id: get().project.nextId };
    get().updateProject((p) => { p.templates = [...p.templates, template]; p.nextId++; });
    return template;
  },
  addNbt: (name, bytes) => {
    const path = `structures/${name.replace(/[^A-Za-z0-9_.-]/g, '_')}`;
    get().updateProject((p) => { p.nbtFiles = { ...p.nbtFiles, [path]: bytes }; });
    return get().addTemplate({
      name: name.replace(/\.nbt$/, ''), kind: 'template', path, size: readNbtSize(bytes) ?? undefined,
      rotation: 'none', mirror: 'none', y: 'surface', yOffset: 0, entities: false,
    });
  },
  updateTemplate: (id, patch) => get().updateProject((p) => { p.templates = p.templates.map((t) => (t.id === id ? { ...t, ...patch } : t)); }),
  removeTemplate: (id) => get().updateProject((p) => {
    p.templates = p.templates.filter((t) => t.id !== id);
    p.instances = p.instances.filter((i) => i.templateId !== id);
  }),
  addInstance: (templateId, x, z) => {
    const id = get().project.nextId;
    get().updateProject((p) => { p.instances = [...p.instances, { id, templateId, x, z, overrides: {} }]; p.nextId++; });
    return id;
  },
  updateInstance: (id, patch) => get().updateProject((p) => { p.instances = p.instances.map((i) => (i.id === id ? { ...i, ...patch } : i)); }),
  removeInstance: (id) => get().updateProject((p) => { p.instances = p.instances.filter((i) => i.id !== id); }),
  importZipBytes: async (bytes) => {
    const { project, warnings } = await importZipAsync(bytes);
    set({ project, undo: [], redo: [], rev: get().rev + 1, messages: warnings.map((text) => ({ level: 'warning' as const, text })) });
  },
  exportZipBytes: () => exportZipAsync(get().project),
  reset: () => set({ project: emptyProject(), undo: [], redo: [], messages: [], rev: get().rev + 1 }),
}));
