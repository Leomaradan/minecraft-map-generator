import { gzipSync } from 'node:zlib';

export interface StructureBlock {
  x: number;
  y: number;
  z: number;
  /** Index in the palette. */
  state: number;
}

/** Minecraft 26.1: templates are upgraded by the game's data fixers when loaded by newer versions. */
export const DATA_VERSION = 4786;

const TAG_END = 0;
const TAG_INT = 3;
const TAG_STRING = 8;
const TAG_LIST = 9;
const TAG_COMPOUND = 10;

/** Raw NBT bytes of a structure template (size, palette, blocks, no entities), uncompressed. */
export function structureNbt(size: [number, number, number], palette: string[], blocks: StructureBlock[]): Buffer {
  const out = new NbtWriter();
  out.byte(TAG_COMPOUND);
  out.string('');
  out.namedInt('DataVersion', DATA_VERSION);
  out.listHeader('size', TAG_INT, 3);
  size.forEach((v) => out.int(v));
  out.listHeader('palette', TAG_COMPOUND, palette.length);
  for (const name of palette) {
    out.byte(TAG_STRING);
    out.string('Name');
    out.string(name);
    out.byte(TAG_END);
  }
  out.listHeader('blocks', TAG_COMPOUND, blocks.length);
  for (const block of blocks) {
    out.listHeader('pos', TAG_INT, 3);
    out.int(block.x);
    out.int(block.y);
    out.int(block.z);
    out.namedInt('state', block.state);
    out.byte(TAG_END);
  }
  out.listHeader('entities', TAG_END, 0);
  out.byte(TAG_END);
  return out.toBuffer();
}

export function gzipNbt(nbt: Buffer): Buffer {
  return gzipSync(nbt, { level: 9 });
}

class NbtWriter {
  private readonly chunks: Buffer[] = [];
  private current = Buffer.alloc(1 << 16);
  private pos = 0;

  private ensure(bytes: number): void {
    if (this.pos + bytes <= this.current.length) return;
    this.chunks.push(this.current.subarray(0, this.pos));
    this.current = Buffer.alloc(Math.max(1 << 16, bytes));
    this.pos = 0;
  }

  byte(value: number): void {
    this.ensure(1);
    this.current[this.pos++] = value;
  }

  int(value: number): void {
    this.ensure(4);
    this.current.writeInt32BE(value, this.pos);
    this.pos += 4;
  }

  string(value: string): void {
    const bytes = Buffer.from(value, 'utf8');
    this.ensure(2 + bytes.length);
    this.current.writeUInt16BE(bytes.length, this.pos);
    bytes.copy(this.current, this.pos + 2);
    this.pos += 2 + bytes.length;
  }

  namedInt(name: string, value: number): void {
    this.byte(TAG_INT);
    this.string(name);
    this.int(value);
  }

  listHeader(name: string, itemType: number, count: number): void {
    this.byte(TAG_LIST);
    this.string(name);
    this.byte(itemType);
    this.int(count);
  }

  toBuffer(): Buffer {
    return Buffer.concat([...this.chunks, this.current.subarray(0, this.pos)]);
  }
}
