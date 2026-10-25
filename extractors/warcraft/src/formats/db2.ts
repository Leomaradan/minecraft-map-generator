import type { DbdField } from './dbd.ts';

export type Db2Value = number | string | number[];
export type Db2Record = Record<string, Db2Value>;

interface FieldStorage {
  offsetBits: number;
  sizeBits: number;
  additionalDataSize: number;
  type: number;
  val1: number;
  val2: number;
  val3: number;
  /** Start of this field's data in the pallet or common data block. */
  extraStart: number;
}

interface Section {
  tactKeyHash: bigint;
  fileOffset: number;
  recordCount: number;
  stringTableSize: number;
  idListSize: number;
  relationshipDataSize: number;
  offsetMapIdCount: number;
  copyTableCount: number;
}

const STORAGE_NONE = 0;
const STORAGE_BITPACKED = 1;
const STORAGE_COMMON = 2;
const STORAGE_PALLET = 3;
const STORAGE_PALLET_ARRAY = 4;
const STORAGE_BITPACKED_SIGNED = 5;

const FLAG_SPARSE = 0x01;
const FLAG_ID_LIST = 0x04;

/** Reads the layout hash of a WDC4/WDC5 file, needed to pick its definition. */
export function db2LayoutHash(data: Buffer): number {
  return data.readUInt32LE(headerStart(data) + 20);
}

function headerStart(data: Buffer): number {
  const magic = data.toString('latin1', 0, 4);
  if (magic === 'WDC5') return 136; // magic, version, 128-byte schema string
  if (magic === 'WDC4') return 4;
  throw new Error(`unsupported DB2 format ${magic}`);
}

/**
 * Reads a WDC4/WDC5 DB2 table into records keyed by ID, naming columns with a
 * WoWDBDefs layout. Encrypted sections (unreleased content) are skipped.
 */
export function readDb2(data: Buffer, fields: DbdField[]): Map<number, Db2Record> {
  let pos = headerStart(data);
  const recordCount = data.readUInt32LE(pos);
  const recordSize = data.readUInt32LE(pos + 8);
  const flags = data.readUInt16LE(pos + 36);
  const idIndex = data.readUInt16LE(pos + 38);
  const totalFieldCount = data.readUInt32LE(pos + 40);
  const storageInfoSize = data.readUInt32LE(pos + 52);
  const commonDataSize = data.readUInt32LE(pos + 56);
  const palletDataSize = data.readUInt32LE(pos + 60);
  const sectionCount = data.readUInt32LE(pos + 64);
  pos += 68;

  if (flags & FLAG_SPARSE) throw new Error('sparse DB2 tables are not supported');

  const sections: Section[] = [];
  for (let i = 0; i < sectionCount; i++, pos += 40) {
    sections.push({
      tactKeyHash: data.readBigUInt64LE(pos),
      fileOffset: data.readUInt32LE(pos + 8),
      recordCount: data.readUInt32LE(pos + 12),
      stringTableSize: data.readUInt32LE(pos + 16),
      idListSize: data.readUInt32LE(pos + 24),
      relationshipDataSize: data.readUInt32LE(pos + 28),
      offsetMapIdCount: data.readUInt32LE(pos + 32),
      copyTableCount: data.readUInt32LE(pos + 36),
    });
  }
  pos += totalFieldCount * 4; // field structures (size, offset): superseded by storage info

  const storages: FieldStorage[] = [];
  let palletOffset = 0;
  let commonOffset = 0;
  for (let i = 0; i < storageInfoSize / 24; i++, pos += 24) {
    const storage: FieldStorage = {
      offsetBits: data.readUInt16LE(pos),
      sizeBits: data.readUInt16LE(pos + 2),
      additionalDataSize: data.readUInt32LE(pos + 4),
      type: data.readUInt32LE(pos + 8),
      val1: data.readUInt32LE(pos + 12),
      val2: data.readUInt32LE(pos + 16),
      val3: data.readUInt32LE(pos + 20),
      extraStart: 0,
    };
    if (storage.type === STORAGE_PALLET || storage.type === STORAGE_PALLET_ARRAY) {
      storage.extraStart = palletOffset;
      palletOffset += storage.additionalDataSize;
    } else if (storage.type === STORAGE_COMMON) {
      storage.extraStart = commonOffset;
      commonOffset += storage.additionalDataSize;
    }
    storages.push(storage);
  }
  const palletStart = pos;
  const commonStart = palletStart + palletDataSize;

  const inlineFields = fields.filter((f) => !f.nonInline);
  if (inlineFields.length !== storages.length) {
    throw new Error(`DB2 has ${storages.length} columns but its definition has ${inlineFields.length}`);
  }
  const commonValues = storages.map((s) => (s.type === STORAGE_COMMON ? readCommon(data, commonStart + s.extraStart, s.additionalDataSize) : undefined));

  // String offsets are relative to the field, in a virtual buffer holding all
  // records of all sections followed by all string tables.
  const strings = Buffer.concat(sections.map((s) => {
    const start = s.fileOffset + s.recordCount * recordSize;
    return s.tactKeyHash === 0n ? data.subarray(start, start + s.stringTableSize) : Buffer.alloc(s.stringTableSize);
  }));

  const idField = fields.find((f) => f.isId);
  const relationField = fields.find((f) => f.isRelation && f.nonInline);
  const records = new Map<number, Db2Record>();
  let globalIndex = 0;

  for (const section of sections) {
    if (section.tactKeyHash !== 0n || section.recordCount === 0) {
      globalIndex += section.recordCount;
      continue;
    }
    let sectionPos = section.fileOffset + section.recordCount * recordSize + section.stringTableSize;
    const ids = new Uint32Array(section.recordCount);
    if (flags & FLAG_ID_LIST && section.idListSize > 0) {
      for (let i = 0; i < section.recordCount; i++) ids[i] = data.readUInt32LE(sectionPos + i * 4);
    }
    sectionPos += section.idListSize;
    const copies: [number, number][] = [];
    for (let i = 0; i < section.copyTableCount; i++, sectionPos += 8) {
      copies.push([data.readUInt32LE(sectionPos), data.readUInt32LE(sectionPos + 4)]);
    }
    sectionPos += section.offsetMapIdCount * 6;
    const relations = new Map<number, number>();
    if (section.relationshipDataSize > 0) {
      const count = data.readUInt32LE(sectionPos);
      for (let i = 0; i < count; i++) {
        const entry = sectionPos + 12 + i * 8;
        relations.set(data.readUInt32LE(entry + 4), data.readUInt32LE(entry));
      }
    }

    for (let r = 0; r < section.recordCount; r++, globalIndex++) {
      const recordStart = section.fileOffset + r * recordSize;
      const record: Db2Record = {};
      inlineFields.forEach((field, column) => {
        const value = readField(data, recordStart, field, storages[column], {
          palletStart,
          common: commonValues[column],
          id: ids[r],
          strings,
          stringBase: globalIndex * recordSize - recordCount * recordSize,
        });
        record[field.name] = value;
      });
      const id = flags & FLAG_ID_LIST ? ids[r] : (record[inlineFields[idIndex].name] as number);
      if (idField) record[idField.name] = id;
      if (relationField) record[relationField.name] = relations.get(r) ?? 0;
      records.set(id, record);
    }
    for (const [newId, sourceId] of copies) {
      const source = records.get(sourceId);
      if (source) records.set(newId, { ...source, ...(idField ? { [idField.name]: newId } : {}) });
    }
  }
  return records;
}

function readCommon(data: Buffer, start: number, size: number): Map<number, number> {
  const values = new Map<number, number>();
  for (let p = start; p < start + size; p += 8) values.set(data.readUInt32LE(p), data.readUInt32LE(p + 4));
  return values;
}

interface FieldContext {
  palletStart: number;
  common: Map<number, number> | undefined;
  id: number;
  strings: Buffer;
  stringBase: number;
}

function readField(data: Buffer, recordStart: number, field: DbdField, storage: FieldStorage, ctx: FieldContext): Db2Value {
  const raw: number[] = [];
  switch (storage.type) {
    case STORAGE_NONE: {
      const elementBits = storage.sizeBits / field.arrayLength;
      for (let i = 0; i < field.arrayLength; i++) {
        raw.push(readBits(data, recordStart, storage.offsetBits + i * elementBits, elementBits));
      }
      break;
    }
    case STORAGE_BITPACKED:
    case STORAGE_BITPACKED_SIGNED: {
      let value = readBits(data, recordStart, storage.offsetBits, storage.sizeBits);
      if (storage.type === STORAGE_BITPACKED_SIGNED && value >= 2 ** (storage.sizeBits - 1)) value -= 2 ** storage.sizeBits;
      raw.push(value);
      break;
    }
    case STORAGE_COMMON:
      raw.push(ctx.common?.get(ctx.id) ?? storage.val1);
      break;
    case STORAGE_PALLET: {
      const index = readBits(data, recordStart, storage.offsetBits, storage.sizeBits);
      raw.push(data.readUInt32LE(ctx.palletStart + storage.extraStart + index * 4));
      break;
    }
    case STORAGE_PALLET_ARRAY: {
      const index = readBits(data, recordStart, storage.offsetBits, storage.sizeBits);
      for (let i = 0; i < storage.val3; i++) {
        raw.push(data.readUInt32LE(ctx.palletStart + storage.extraStart + (index * storage.val3 + i) * 4));
      }
      break;
    }
    default:
      throw new Error(`unsupported DB2 storage type ${storage.type}`);
  }

  const values = raw.map((value, i) => {
    switch (field.type) {
      case 'float': {
        const buffer = Buffer.alloc(4);
        buffer.writeUInt32LE(value >>> 0);
        return buffer.readFloatLE(0);
      }
      case 'string':
      case 'locstring': {
        if (value === 0) return '';
        const offset = ctx.stringBase + storage.offsetBits / 8 + i * 4 + value;
        const end = ctx.strings.indexOf(0, offset);
        return ctx.strings.toString('utf8', offset, end < 0 ? undefined : end);
      }
      default: {
        const bits = field.size || 32;
        if (field.signed && bits <= 32 && value >= 2 ** (bits - 1)) return value - 2 ** bits;
        return value;
      }
    }
  });
  return field.arrayLength > 1 ? (values as number[]) : values[0];
}

/** Reads an unsigned little-endian bit field (up to 53 bits) of a record. */
function readBits(data: Buffer, recordStart: number, offsetBits: number, sizeBits: number): number {
  const byteStart = recordStart + (offsetBits >>> 3);
  const shift = offsetBits & 7;
  const byteCount = Math.ceil((shift + sizeBits) / 8);
  let value = 0;
  for (let i = byteCount - 1; i >= 0; i--) value = value * 256 + data[byteStart + i];
  value = Math.floor(value / 2 ** shift);
  return value % 2 ** sizeBits;
}
