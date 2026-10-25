import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

export interface ArchiveLocation {
  archive: number;
  offset: number;
  size: number;
}

interface Bucket {
  data: Buffer;
  keyBytes: number;
  offsetBytes: number;
  sizeBytes: number;
  offsetBits: number;
  entrySize: number;
  start: number;
  count: number;
}

/**
 * The local `.idx` files: 16 buckets mapping truncated encoding keys to a
 * position in a `data.NNN` archive. Only the newest version of each bucket is used.
 */
export class LocalIndex {
  private readonly buckets: (Bucket | undefined)[] = new Array(16);

  constructor(dataDir: string) {
    const newest = new Map<number, { version: number; file: string }>();
    for (const file of readdirSync(dataDir)) {
      const match = /^([0-9a-f]{2})([0-9a-f]{8})\.idx$/i.exec(file);
      if (!match) continue;
      const bucket = parseInt(match[1], 16);
      const version = parseInt(match[2], 16);
      const current = newest.get(bucket);
      if (!current || version > current.version) newest.set(bucket, { version, file });
    }
    for (const [bucket, { file }] of newest) {
      this.buckets[bucket] = readBucket(readFileSync(join(dataDir, file)));
    }
  }

  find(eKey: Buffer): ArchiveLocation | undefined {
    const bucket = this.buckets[bucketOf(eKey)];
    if (!bucket) return undefined;
    const key = eKey.subarray(0, bucket.keyBytes);
    let low = 0;
    let high = bucket.count - 1;
    while (low <= high) {
      const mid = (low + high) >>> 1;
      const pos = bucket.start + mid * bucket.entrySize;
      const cmp = key.compare(bucket.data, pos, pos + bucket.keyBytes);
      if (cmp === 0) return readLocation(bucket, pos + bucket.keyBytes);
      if (cmp > 0) low = mid + 1;
      else high = mid - 1;
    }
    return undefined;
  }
}

function bucketOf(eKey: Buffer): number {
  let x = 0;
  for (let i = 0; i < 9; i++) x ^= eKey[i];
  return (x & 0x0f) ^ (x >> 4);
}

function readBucket(data: Buffer): Bucket {
  // Header: u32 size, u32 hash, then the 16-byte header proper, padded to 0x20.
  const sizeBytes = data[12];
  const offsetBytes = data[13];
  const keyBytes = data[14];
  const offsetBits = data[15];
  const entriesSize = data.readUInt32LE(0x20);
  const entrySize = keyBytes + offsetBytes + sizeBytes;
  return { data, keyBytes, offsetBytes, sizeBytes, offsetBits, entrySize, start: 0x28, count: Math.floor(entriesSize / entrySize) };
}

function readLocation(bucket: Bucket, pos: number): ArchiveLocation {
  // Archive offset field (big-endian): high bits are the archive number, the low
  // `offsetBits` bits the offset in it. Size is little-endian.
  const packed = bucket.data.readUIntBE(pos, bucket.offsetBytes);
  const divisor = 2 ** bucket.offsetBits;
  return {
    archive: Math.floor(packed / divisor),
    offset: packed % divisor,
    size: bucket.data.readUIntLE(pos + bucket.offsetBytes, bucket.sizeBytes),
  };
}
