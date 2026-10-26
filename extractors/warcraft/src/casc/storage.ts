import { closeSync, existsSync, openSync, readFileSync, readSync } from 'node:fs';
import { join } from 'node:path';
import { decodeBlte } from './blte.ts';
import { LocalIndex } from './localIndex.ts';
import { EncodingTable } from './encoding.ts';
import { RootTable } from './root.ts';

export interface BuildInfo {
  product: string;
  version: string;
  buildKey: string;
  cdnKey: string;
  tags: string;
}

/** Reads `.build.info` (a pipe-separated table with typed headers like `Build Key!HEX:16`). */
export function readBuildInfo(installDir: string): BuildInfo[] {
  const lines = readFileSync(join(installDir, '.build.info'), 'utf8').split(/\r?\n/).filter((l) => l.trim() !== '');
  const headers = lines[0].split('|').map((h) => h.split('!')[0]);
  return lines.slice(1).map((line) => {
    const values = line.split('|');
    const row = (name: string) => values[headers.indexOf(name)] ?? '';
    return {
      product: row('Product'),
      version: row('Version'),
      buildKey: row('Build Key'),
      cdnKey: row('CDN Key'),
      tags: row('Tags'),
    };
  });
}

/** Parses a build/CDN config file: `key = value value ...` lines. */
function readConfig(dataDir: string, hash: string): Map<string, string[]> {
  const path = join(dataDir, 'config', hash.slice(0, 2), hash.slice(2, 4), hash);
  const config = new Map<string, string[]>();
  for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const match = /^([\w-]+)\s*=\s*(.*)$/.exec(line);
    if (match) config.set(match[1], match[2].trim().split(/\s+/));
  }
  return config;
}

// Locale flags of the CASC root file.
const LOCALE_FLAGS: Record<string, number> = {
  enUS: 0x2, koKR: 0x4, frFR: 0x10, deDE: 0x20, zhCN: 0x40, esES: 0x80, zhTW: 0x100,
  enGB: 0x200, esMX: 0x1000, ruRU: 0x2000, ptBR: 0x4000, itIT: 0x8000, ptPT: 0x10000,
};

/**
 * Read-only access to a local CASC storage (a World of Warcraft installation).
 * Files are addressed by FileDataID: root (FDID -> content key), encoding
 * (content key -> encoding key), local indices (encoding key -> data.NNN offset).
 */
export class CascStorage {
  readonly build: BuildInfo;
  readonly locale: string;
  private readonly dataDir: string;
  private readonly index: LocalIndex;
  private readonly encoding: EncodingTable;
  private readonly root: RootTable;
  private readonly archives = new Map<number, number>();

  constructor(installDir: string, product = 'wow') {
    const builds = readBuildInfo(installDir);
    const build = builds.find((b) => b.product === product);
    if (!build) {
      throw new Error(`product '${product}' not found in .build.info (available: ${builds.map((b) => b.product).join(', ')})`);
    }
    this.build = build;
    this.locale = Object.keys(LOCALE_FLAGS).find((l) => build.tags.includes(`${l} text`)) ?? 'enUS';
    this.dataDir = join(installDir, 'Data');

    const buildConfig = readConfig(this.dataDir, build.buildKey);
    this.index = new LocalIndex(join(this.dataDir, 'data'));

    const encodingEKey = buildConfig.get('encoding')?.[1];
    if (!encodingEKey) throw new Error('build config has no encoding key');
    const encodingData = this.readByEKey(Buffer.from(encodingEKey, 'hex'));
    if (!encodingData) throw new Error('encoding file is not in local storage');
    this.encoding = new EncodingTable(encodingData);

    const rootCKey = buildConfig.get('root')?.[0];
    if (!rootCKey) throw new Error('build config has no root key');
    const rootData = this.readByCKey(Buffer.from(rootCKey, 'hex'));
    if (!rootData) throw new Error('root file is not in local storage');
    this.root = new RootTable(rootData);
  }

  /** True if the FileDataID is known by this build (it may still be missing locally). */
  hasFile(fileDataId: number): boolean {
    return this.root.find(fileDataId).length > 0;
  }

  /**
   * Reads a file by FileDataID. Returns undefined when the file is unknown or its
   * data is not stored locally. Throws EncryptedError for encrypted content, unless
   * `zeroEncrypted` is set (see decodeBlte).
   */
  readFile(fileDataId: number, zeroEncrypted = false): Buffer | undefined {
    const preferred = LOCALE_FLAGS[this.locale] ?? LOCALE_FLAGS.enUS;
    const entries = this.root.find(fileDataId).sort((a, b) => score(b) - score(a));
    for (const entry of entries) {
      const data = this.readByCKey(entry.cKey, zeroEncrypted);
      if (data) return data;
    }
    return undefined;

    function score(entry: { localeFlags: number; contentFlags: number }) {
      let s = 0;
      if (entry.localeFlags & preferred) s += 4;
      if (entry.localeFlags & LOCALE_FLAGS.enUS) s += 2;
      if (!(entry.contentFlags & 0x80)) s += 1; // not "low violence"
      return s;
    }
  }

  private readByCKey(cKey: Buffer, zeroEncrypted = false): Buffer | undefined {
    for (const eKey of this.encoding.findEKeys(cKey)) {
      const data = this.readByEKey(eKey, false, zeroEncrypted);
      if (data) return data;
    }
    return undefined;
  }

  private readByEKey(eKey: Buffer, required = true, zeroEncrypted = false): Buffer | undefined {
    const location = this.index.find(eKey);
    if (!location) {
      if (required) throw new Error(`encoding key ${eKey.toString('hex')} not found in local storage`);
      return undefined;
    }
    // Each entry in data.NNN starts with a 30-byte header (key, size, flags, checksums).
    const raw = Buffer.alloc(location.size - 30);
    readSync(this.archive(location.archive), raw, 0, raw.length, location.offset + 30);
    return decodeBlte(raw, zeroEncrypted);
  }

  private archive(index: number): number {
    let fd = this.archives.get(index);
    if (fd === undefined) {
      fd = openSync(join(this.dataDir, 'data', `data.${String(index).padStart(3, '0')}`), 'r');
      this.archives.set(index, fd);
    }
    return fd;
  }

  close(): void {
    for (const fd of this.archives.values()) closeSync(fd);
    this.archives.clear();
  }
}

/** Lists products installed in a WoW directory (`wow`, `wow_classic`, ...). */
export function listProducts(installDir: string): BuildInfo[] {
  if (!existsSync(join(installDir, '.build.info'))) return [];
  return readBuildInfo(installDir);
}
