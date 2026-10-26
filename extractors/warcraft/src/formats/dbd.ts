import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

/** A column of a DB2 layout, as described by a WoWDBDefs definition. */
export interface DbdField {
  name: string;
  type: 'int' | 'float' | 'string' | 'locstring';
  /** Integer size in bits (8, 16, 32, 64); 0 for floats and strings. */
  size: number;
  signed: boolean;
  arrayLength: number;
  isId: boolean;
  /** Not stored in the record: the ID list (`id`) or the relationship map (`relation`). */
  nonInline: boolean;
  isRelation: boolean;
}

const DBD_URL = 'https://raw.githubusercontent.com/wowdev/WoWDBDefs/master/definitions';

export function defaultCacheDir(): string {
  return join(process.env.XDG_CACHE_HOME ?? join(homedir(), '.cache'), 'warcraft-map-extractor');
}

/**
 * Returns the fields of `table` for the DB2 layout hash, from the cached
 * WoWDBDefs definition, downloading it when missing or outdated.
 */
export async function getLayout(table: string, layoutHash: number, cacheDir = defaultCacheDir()): Promise<DbdField[]> {
  const hash = layoutHash.toString(16).toUpperCase().padStart(8, '0');
  const path = join(cacheDir, 'dbd', `${table}.dbd`);
  if (existsSync(path)) {
    const fields = parseDbd(readFileSync(path, 'utf8'), hash);
    if (fields) return fields;
  }
  const response = await fetch(`${DBD_URL}/${table}.dbd`);
  if (!response.ok) throw new Error(`cannot download ${table}.dbd from WoWDBDefs: HTTP ${response.status}`);
  const text = await response.text();
  mkdirSync(join(cacheDir, 'dbd'), { recursive: true });
  writeFileSync(path, text);
  const fields = parseDbd(text, hash);
  if (!fields) throw new Error(`WoWDBDefs has no definition of ${table} for layout ${hash} (game build too new?)`);
  return fields;
}

/** Parses a .dbd file and returns the fields of the definition listing `layoutHash`. */
export function parseDbd(text: string, layoutHash: string): DbdField[] | undefined {
  const sections = text.replace(/\r/g, '').split(/\n\s*\n/);
  const columns = new Map<string, DbdField['type']>();
  for (const line of sections[0].split('\n').slice(1)) {
    const match = /^(int|float|string|locstring)(?:<[^>]*>)?\s+(\w+)/.exec(line.trim());
    if (match) columns.set(match[2], match[1] as DbdField['type']);
  }

  for (const section of sections.slice(1)) {
    const lines = section.split('\n').map((l) => l.trim()).filter((l) => l !== '');
    const layouts = lines.filter((l) => l.startsWith('LAYOUT ')).flatMap((l) => l.slice(7).split(',').map((h) => h.trim().toUpperCase()));
    if (!layouts.includes(layoutHash)) continue;
    return lines
      .filter((l) => !/^(LAYOUT|BUILD|COMMENT)\b/.test(l))
      .map((l) => parseFieldLine(l, columns));
  }
  return undefined;
}

function parseFieldLine(line: string, columns: Map<string, DbdField['type']>): DbdField {
  const match = /^(?:\$([^$]*)\$)?(\w+)(?:<(u?)(\d+)>)?(?:\[(\d+)\])?/.exec(line.replace(/\/\/.*$/, '').trim());
  if (!match) throw new Error(`invalid DBD field line: ${line}`);
  const annotations = (match[1] ?? '').split(',');
  const name = match[2];
  const type = columns.get(name);
  if (!type) throw new Error(`DBD field ${name} has no column definition`);
  return {
    name,
    type,
    size: match[4] ? Number(match[4]) : 0,
    signed: match[3] !== 'u',
    arrayLength: match[5] ? Number(match[5]) : 1,
    isId: annotations.includes('id'),
    nonInline: annotations.includes('noninline'),
    isRelation: annotations.includes('relation'),
  };
}
