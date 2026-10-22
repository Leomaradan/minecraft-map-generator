import Ajv2020 from 'ajv/dist/2020';
import biomesSchema from '../../../docs/schemas/biomes.schema.json';
import mapSchema from '../../../docs/schemas/map.schema.json';
import type { Project } from './project';

export interface Message {
  level: 'error' | 'warning';
  text: string;
}

const ajv = new Ajv2020({ allErrors: true, strict: false });
const validateMap = ajv.compile(mapSchema);
ajv.addSchema(biomesSchema);
const entrySchemaId = `${(biomesSchema as { $id: string }).$id}#/$defs/entry`;

function errorsOf(errors: { instancePath: string; message?: string }[] | null | undefined, prefix: string): Message[] {
  return (errors ?? []).map((e) => ({ level: 'error', text: `${prefix}${e.instancePath || ' (root)'}: ${e.message ?? 'invalid'}` }));
}

export function mapConfigErrors(config: object): Message[] {
  validateMap(config);
  return errorsOf(validateMap.errors, 'map.json');
}

export function biomeEntryErrors(entry: object, name: string): Message[] {
  const v = ajv.getSchema(entrySchemaId);
  if (!v) return [];
  v(entry);
  return errorsOf(v.errors, `biomes.json ${name}`);
}

/** `lowFluidPixels`: number of fluid pixels whose surface is not above the ground (computed by the worker). */
export function validateProject(p: Project, lowFluidPixels = 0): Message[] {
  const out: Message[] = [];
  const hm = p.heightmap;
  if (!hm) {
    out.push({ level: 'error', text: 'No heightmap: upload one in General' });
    return out;
  }
  out.push(...mapConfigErrors(p.config));
  for (const b of p.biomes) out.push(...biomeEntryErrors(b.data, b.color));
  if (p.biomes.length > 0 && !p.biomeIndex.some((v) => v > 0)) {
    out.push({ level: 'warning', text: 'Biomes are defined but nothing is painted: biomes.png is not exported' });
  }
  const seen = new Map<string, number>();
  for (const b of p.biomes) seen.set(b.color, (seen.get(b.color) ?? 0) + 1);
  for (const [c, count] of seen) if (count > 1) out.push({ level: 'error', text: `Color ${c} is used by ${count} biomes` });

  if (lowFluidPixels > 0) {
    out.push({ level: 'warning', text: `${lowFluidPixels} fluid pixel(s) have a surface at or below the ground: nothing is placed there` });
  }
  for (const i of p.instances) {
    if (i.x < 0 || i.z < 0 || i.x >= hm.width || i.z >= hm.height) out.push({ level: 'warning', text: `A structure is outside the map (${i.x}, ${i.z})` });
  }
  for (const t of p.templates) {
    if (t.kind === 'template' && (!t.path || !p.nbtFiles[t.path])) out.push({ level: 'error', text: `Template "${t.name}" has no .nbt file` });
    if (t.kind === 'structure' && !t.structureId) out.push({ level: 'error', text: `Structure "${t.name}" has no id` });
  }
  return out;
}
