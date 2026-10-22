import { strFromU8, strToU8, unzipSync, zipSync } from 'fflate';
import { solveFluids } from './fluids';
import { resolveHeightMapping } from './heightMapping';
import { readNbtSize } from './nbt';
import { decodeGrayPng, decodeRgbPng, encodeGrayPng, encodeRgbPng } from './png';
import {
  emptyProject,
  type BiomeEntry,
  type FluidBody,
  type Json,
  type JsonObject,
  type Project,
  type StructureInstance,
  type StructureTemplate,
} from './project';

const SCHEMAS = 'https://raw.githubusercontent.com/Leomaradan/minecraft-map-generator/main/docs/schemas/';
export const EDITOR_VERSION = 1;

export interface ImportResult {
  project: Project;
  warnings: string[];
}

function hex(c: number): string {
  return c.toString(16).padStart(2, '0');
}

export function rgbToHex(r: number, g: number, b: number): string {
  return `#${hex(r)}${hex(g)}${hex(b)}`.toUpperCase();
}

export function parseColor(text: string): [number, number, number] | null {
  const s = text.trim();
  let m = /^(?:#|0x)?([0-9a-f]{6})$/i.exec(s);
  if (m) {
    const v = parseInt(m[1], 16);
    return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
  }
  m = /^(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})$/.exec(s);
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
}

function pngMime(name: string): string {
  return /\.jpe?g$/i.test(name) ? 'image/jpeg' : 'image/png';
}

/** Removes the single top-level folder that may wrap the whole ZIP. */
function stripRoot(files: Record<string, Uint8Array>): Record<string, Uint8Array> {
  const names = Object.keys(files).filter((n) => !n.endsWith('/'));
  if (names.includes('map.json')) return files;
  const roots = new Set(names.map((n) => n.split('/')[0]));
  if (roots.size !== 1) return files;
  const prefix = `${[...roots][0]}/`;
  if (!names.every((n) => n.startsWith(prefix))) return files;
  const out: Record<string, Uint8Array> = {};
  for (const n of names) out[n.slice(prefix.length)] = files[n];
  return out;
}

function parseJson(bytes: Uint8Array, name: string): JsonObject {
  try {
    const v = JSON.parse(strFromU8(bytes)) as Json;
    if (v === null || typeof v !== 'object') throw new Error('not an object');
    return v as JsonObject;
  } catch (e) {
    throw new Error(`${name} is not valid JSON: ${(e as Error).message}`);
  }
}

function folderOf(path: string): string {
  return path.endsWith('/') ? path : `${path}/`;
}

export function importZip(zipBytes: Uint8Array): ImportResult {
  const warnings: string[] = [];
  const files = stripRoot(unzipSync(zipBytes));
  const config = files['map.json'] ? parseJson(files['map.json'], 'map.json') : null;
  if (!config) throw new Error('map.json is missing');
  const fileNames = {
    heightmap: 'heightmap.png',
    biomes: 'biomes.png',
    biome_mapping: 'biomes.json',
    water: 'water.png',
    lava: 'lava.png',
    structures: 'structures.json',
    datapack: 'datapack/',
    ...((config.files as Record<string, string> | undefined) ?? {}),
  };
  delete config.files;
  delete config.$schema;

  const project = emptyProject();
  project.config = config;

  const hmBytes = files[fileNames.heightmap];
  if (!hmBytes) throw new Error(`${fileNames.heightmap} is missing`);
  const hm = decodeGrayPng(hmBytes, fileNames.heightmap);
  warnings.push(...hm.warnings);
  project.heightmap = hm.raster;
  project.heightmapBytes = hmBytes;
  const { width, height, bitDepth } = hm.raster;
  const n = width * height;
  project.fluidLabels = new Uint16Array(n);
  project.fluidRaw = new Uint16Array(n);
  project.biomeIndex = new Uint16Array(n);

  const editor = files['editor.json'] ? parseJson(files['editor.json'], 'editor.json') : null;

  // Water and lava
  const readFluid = (name: string) => {
    const bytes = files[name];
    if (!bytes) return null;
    const { raster, warnings: w } = decodeGrayPng(bytes, name);
    warnings.push(...w);
    if (raster.width !== width || raster.height !== height || raster.bitDepth !== bitDepth) {
      throw new Error(`${name} must have the size and bit depth of the heightmap`);
    }
    return raster.data;
  };
  const water = readFluid(fileNames.water);
  const lava = readFluid(fileNames.lava);
  const groundData = hm.raster.data;
  const isLava = (p: number) => lava !== null && lava[p] > 0 && lava[p] > groundData[p];

  const editorBodies = (editor?.bodies as unknown as FluidBody[] | undefined) ?? null;
  const labelsBytes = files['editor/fluid-bodies.png'];
  if (editorBodies && labelsBytes) {
    const labels = decodeGrayPng(labelsBytes, 'editor/fluid-bodies.png').raster;
    if (labels.width === width && labels.height === height) {
      project.fluidLabels = Uint16Array.from(labels.data);
      project.bodies = editorBodies;
    }
  }
  if (project.bodies.length === 0 && (water || lava)) {
    const labels = project.fluidLabels;
    const kind = new Uint8Array(n); // 1 water, 2 lava
    for (let p = 0; p < n; p++) {
      if (isLava(p)) kind[p] = 2;
      else if (water && water[p] > 0) kind[p] = 1;
    }
    for (const k of [1, 2]) {
      // One body per fluid: its pixels keep their exact levels until the body is changed to a lake or river.
      let any = false;
      const id = project.nextId;
      for (let p = 0; p < n; p++) {
        if (kind[p] === k) {
          labels[p] = id;
          any = true;
        }
      }
      if (!any) continue;
      project.nextId++;
      project.bodies.push({ id, name: k === 1 ? 'Water (imported)' : 'Lava (imported)', fluid: k === 1 ? 'water' : 'lava', mode: 'raw', offset: 0 });
    }
  }
  const fluidOf = new Map(project.bodies.map((b) => [b.id, b.fluid]));
  for (let p = 0; p < n; p++) {
    const f = fluidOf.get(project.fluidLabels[p]);
    if (f === 'water') project.fluidRaw[p] = water ? water[p] : 0;
    else if (f === 'lava') project.fluidRaw[p] = lava ? lava[p] : 0;
  }

  // Biomes
  const colorToEntry = new Map<string, BiomeEntry>();
  const mappingBytes = files[fileNames.biome_mapping];
  if (mappingBytes) {
    const mapping = parseJson(mappingBytes, fileNames.biome_mapping);
    for (const [key, value] of Object.entries(mapping)) {
      if (key === '$schema') continue;
      const rgb = parseColor(key);
      if (!rgb) {
        warnings.push(`Biome color "${key}" is not a valid color; skipped`);
        continue;
      }
      const data: JsonObject = typeof value === 'string' ? { biome: value } : { ...(value as JsonObject) };
      const entry: BiomeEntry = { id: project.nextId++, color: rgbToHex(...rgb), data };
      project.biomes.push(entry);
      colorToEntry.set(entry.color, entry);
    }
  }
  const biomesBytes = files[fileNames.biomes];
  if (biomesBytes && project.biomes.length > 0) {
    const img = decodeRgbPng(biomesBytes, fileNames.biomes);
    if (img.width !== width || img.height !== height) throw new Error(`${fileNames.biomes} must have the size of the heightmap`);
    const cache = new Map<number, number>();
    const palette = project.biomes.map((b) => ({ id: b.id, rgb: parseColor(b.color)! }));
    for (let p = 0; p < n; p++) {
      const key = (img.rgb[p * 3] << 16) | (img.rgb[p * 3 + 1] << 8) | img.rgb[p * 3 + 2];
      let id = cache.get(key);
      if (id === undefined) {
        let best = Infinity;
        id = palette[0].id;
        for (const e of palette) {
          const d = (e.rgb[0] - img.rgb[p * 3]) ** 2 + (e.rgb[1] - img.rgb[p * 3 + 1]) ** 2 + (e.rgb[2] - img.rgb[p * 3 + 2]) ** 2;
          if (d < best) {
            best = d;
            id = e.id;
          }
        }
        cache.set(key, id);
      }
      project.biomeIndex[p] = id;
    }
  }
  const defaultColor = editor?.defaultBiomeColor;
  const defaultEntry = typeof defaultColor === 'string' ? colorToEntry.get(defaultColor) : undefined;
  project.defaultBiomeId = defaultEntry?.id ?? null;

  // Structures
  const structBytes = files[fileNames.structures];
  const templateByKey = new Map<string, StructureTemplate>();
  const editorTemplates = editor?.templates as unknown as StructureTemplate[] | undefined;
  const editorInstances = editor?.instances as unknown as StructureInstance[] | undefined;
  for (const [path, bytes] of Object.entries(files)) {
    if (path.startsWith('structures/') && path.endsWith('.nbt')) project.nbtFiles[path] = bytes;
  }
  if (editorTemplates && editorInstances) {
    project.templates = editorTemplates;
    project.instances = editorInstances;
    for (const t of project.templates) if (t.path && project.nbtFiles[t.path]) t.size = readNbtSize(project.nbtFiles[t.path]) ?? undefined;
  } else if (structBytes) {
    const raw = JSON.parse(strFromU8(structBytes)) as Json;
    const world = Array.isArray(raw) || (raw as JsonObject).coordinates === 'world';
    const list = (Array.isArray(raw) ? raw : ((raw as JsonObject).structures ?? [])) as JsonObject[];
    if (Array.isArray(raw)) warnings.push('structures.json uses the old list format; positions are read as world coordinates');
    const scale = (config.horizontal_scale as number | undefined) ?? 1;
    const origin = (config.origin as number[] | undefined) ?? [-Math.floor((width * scale) / 2), -Math.floor((height * scale) / 2)];
    for (const e of list) {
      let x = e.x as number;
      let z = e.z as number;
      if (world) {
        x = Math.floor((x - origin[0]) / scale);
        z = Math.floor((z - origin[1]) / scale);
      }
      const isTemplate = e.type === 'template' || (e.type === undefined && e.path !== undefined);
      const key = JSON.stringify({ ...e, x: 0, z: 0 });
      let t = templateByKey.get(key);
      if (!t) {
        const path = e.path as string | undefined;
        t = {
          id: project.nextId++,
          name: isTemplate ? (path ?? 'template').replace(/^structures\//, '').replace(/\.nbt$/, '') : String(e.id),
          kind: isTemplate ? 'template' : 'structure',
          path,
          size: path && project.nbtFiles[path] ? (readNbtSize(project.nbtFiles[path]) ?? undefined) : undefined,
          structureId: e.id as string | undefined,
          fallbackId: e.fallback_id as string | undefined,
          rotation: (e.rotation as StructureTemplate['rotation']) ?? 'none',
          mirror: (e.mirror as StructureTemplate['mirror']) ?? 'none',
          y: (e.y as number | 'surface' | undefined) ?? 'surface',
          yOffset: (e.y_offset as number | undefined) ?? 0,
          entities: (e.entities as boolean | undefined) ?? false,
        };
        templateByKey.set(key, t);
        project.templates.push(t);
      }
      project.instances.push({ id: project.nextId++, templateId: t.id, x, z, overrides: {} });
    }
  }

  // Reference images, other entries
  const refMeta = editor?.references as unknown as { name?: string; file: string; opacity?: number; visible?: boolean }[] | undefined;
  for (const meta of refMeta ?? []) {
    const bytes = files[meta.file];
    if (!bytes) continue;
    project.references.push({
      id: project.nextId++,
      name: meta.name ?? meta.file,
      bytes,
      mime: pngMime(meta.file),
      opacity: meta.opacity ?? 0.5,
      visible: meta.visible ?? true,
    });
  }
  const legacyRef = Object.keys(files).find((f) => f.startsWith('editor/reference.'));
  if (!refMeta && legacyRef) {
    const opacity = ((editor?.reference as JsonObject | undefined)?.opacity as number | undefined) ?? 0.5;
    project.references.push({ id: project.nextId++, name: 'Reference', bytes: files[legacyRef], mime: pngMime(legacyRef), opacity, visible: true });
  }
  const hiddenColors = new Set((editor?.hiddenBiomes as string[] | undefined) ?? []);
  for (const b of project.biomes) if (hiddenColors.has(b.color)) b.hidden = true;
  const dp = folderOf(fileNames.datapack);
  for (const [path, bytes] of Object.entries(files)) {
    if (path.startsWith(dp)) project.passthrough[`datapack/${path.slice(dp.length)}`] = bytes;
  }
  if (typeof editor?.nextId === 'number') project.nextId = Math.max(project.nextId, editor.nextId);
  return { project, warnings };
}

function compactEntry(data: JsonObject): Json {
  const keys = Object.keys(data);
  return keys.length === 1 && keys[0] === 'biome' ? (data.biome as Json) : data;
}

export function resolvedTemplate(project: Project, inst: StructureInstance): StructureTemplate | null {
  const t = project.templates.find((x) => x.id === inst.templateId);
  return t ? { ...t, ...inst.overrides } : null;
}

export function exportZip(project: Project): Uint8Array {
  if (!project.heightmap || !project.heightmapBytes) throw new Error('Upload a heightmap first');
  const hm = project.heightmap;
  const n = hm.width * hm.height;
  const out: Record<string, Uint8Array> = {};
  const mapJson: JsonObject = { $schema: `${SCHEMAS}map.schema.json`, ...project.config };
  out['heightmap.png'] = project.heightmapBytes;

  const mapping = resolveHeightMapping(project.config.height as never, hm.bitDepth);
  const fluids = solveFluids(project.fluidLabels, project.fluidRaw, project.bodies, hm, mapping);
  if (fluids.water.some((v) => v > 0)) out['water.png'] = encodeGrayPng({ ...hm, data: fluids.water });
  if (fluids.lava.some((v) => v > 0)) out['lava.png'] = encodeGrayPng({ ...hm, data: fluids.lava });

  const editor: JsonObject = { editorVersion: EDITOR_VERSION, nextId: project.nextId };
  if (project.bodies.length > 0) {
    out['editor/fluid-bodies.png'] = encodeGrayPng({ width: hm.width, height: hm.height, bitDepth: 16, data: project.fluidLabels });
    editor.bodies = project.bodies as unknown as Json;
  }

  const painted = project.biomeIndex.some((v) => v > 0);
  if (project.biomes.length > 0 && painted) {
    const fallback = project.biomes.find((b) => b.id === project.defaultBiomeId) ?? project.biomes[0];
    const colors = new Map(project.biomes.map((b) => [b.id, parseColor(b.color)!]));
    const rgb = new Uint8Array(n * 3);
    for (let p = 0; p < n; p++) rgb.set(colors.get(project.biomeIndex[p]) ?? colors.get(fallback.id)!, p * 3);
    out['biomes.png'] = encodeRgbPng(hm.width, hm.height, rgb);
    const mapping: JsonObject = { $schema: `${SCHEMAS}biomes.schema.json` };
    for (const b of project.biomes) mapping[b.color] = compactEntry(b.data);
    out['biomes.json'] = strToU8(JSON.stringify(mapping, null, 2));
    editor.defaultBiomeColor = fallback.color;
  }

  if (project.instances.length > 0) {
    const list: JsonObject[] = [];
    for (const inst of project.instances) {
      const t = resolvedTemplate(project, inst);
      if (!t) continue;
      if (t.kind === 'template') {
        const e: JsonObject = { type: 'template', path: t.path!, x: inst.x, y: t.y, z: inst.z };
        if (t.yOffset !== 0) e.y_offset = t.yOffset;
        if (t.rotation !== 'none') e.rotation = t.rotation;
        if (t.mirror !== 'none') e.mirror = t.mirror;
        if (t.entities) e.entities = true;
        list.push(e);
        if (t.path && project.nbtFiles[t.path]) out[t.path] = project.nbtFiles[t.path];
      } else {
        const e: JsonObject = { type: 'structure', id: t.structureId!, x: inst.x, z: inst.z };
        if (t.fallbackId) e.fallback_id = t.fallbackId;
        list.push(e);
      }
    }
    out['structures.json'] = strToU8(JSON.stringify({ $schema: `${SCHEMAS}structures.schema.json`, structures: list }, null, 2));
  }
  if (project.templates.length > 0) {
    editor.templates = project.templates as unknown as Json;
    editor.instances = project.instances as unknown as Json;
    for (const t of project.templates) if (t.path && project.nbtFiles[t.path]) out[t.path] = project.nbtFiles[t.path];
  }

  if (project.references.length > 0) {
    editor.references = project.references.map((r, i) => {
      const ext = r.mime === 'image/jpeg' ? 'jpg' : 'png';
      const file = `editor/references/${i + 1}.${ext}`;
      out[file] = r.bytes;
      return { name: r.name, file, opacity: r.opacity, visible: r.visible };
    });
  }
  const hidden = project.biomes.filter((b) => b.hidden).map((b) => b.color);
  if (hidden.length > 0) editor.hiddenBiomes = hidden;
  for (const [path, bytes] of Object.entries(project.passthrough)) out[path] = bytes;

  out['map.json'] = strToU8(JSON.stringify(mapJson, null, 2));
  out['editor.json'] = strToU8(JSON.stringify(editor, null, 2));
  return zipSync(out, { level: 6 });
}
