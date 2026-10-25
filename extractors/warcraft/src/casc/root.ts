export interface RootEntry {
  cKey: Buffer;
  localeFlags: number;
  contentFlags: number;
}

interface RootBlock {
  localeFlags: number;
  contentFlags: number;
  fileDataIds: Uint32Array;
  cKeysStart: number;
}

const NO_NAMES = 0x10000000;

/**
 * The WoW root file: blocks of (FileDataID, content key) entries, each block
 * sharing locale and content flags. Supports the MFST format (8.2+), including
 * the 10.1.7+ header and the 11.1+ block flags layout.
 */
export class RootTable {
  private readonly data: Buffer;
  private readonly blocks: RootBlock[] = [];

  constructor(data: Buffer) {
    this.data = data;
    const magic = data.toString('latin1', 0, 4);
    if (magic !== 'TSFM' && magic !== 'MFST') {
      throw new Error('legacy (pre-8.2) root format is not supported');
    }
    let offset: number;
    let version = 0;
    {
      const second = data.readUInt32LE(4);
      const third = data.readUInt32LE(8);
      if (third === 1 || third === 2) {
        version = third;
        offset = second; // header size
      } else {
        offset = 12;
      }
    }

    while (offset < data.length) {
      const count = data.readUInt32LE(offset);
      let contentFlags: number;
      let localeFlags: number;
      if (version === 2) {
        localeFlags = data.readUInt32LE(offset + 4);
        contentFlags = (data.readUInt32LE(offset + 8) | data.readUInt32LE(offset + 12) | (data[offset + 16] << 17)) >>> 0;
        offset += 17;
      } else {
        contentFlags = data.readUInt32LE(offset + 4);
        localeFlags = data.readUInt32LE(offset + 8);
        offset += 12;
      }

      const fileDataIds = new Uint32Array(count);
      let id = -1;
      for (let i = 0; i < count; i++) {
        id += data.readInt32LE(offset + i * 4) + 1;
        fileDataIds[i] = id;
      }
      offset += count * 4;
      // Content keys, then (unless the block has no names) 64-bit name hashes.
      const blockHasNames = !(contentFlags & NO_NAMES);
      this.blocks.push({ localeFlags, contentFlags, fileDataIds, cKeysStart: offset });
      offset += count * 16 + (blockHasNames ? count * 8 : 0);
    }
  }

  find(fileDataId: number): RootEntry[] {
    const entries: RootEntry[] = [];
    for (const block of this.blocks) {
      const ids = block.fileDataIds;
      let low = 0;
      let high = ids.length - 1;
      while (low <= high) {
        const mid = (low + high) >>> 1;
        if (ids[mid] < fileDataId) low = mid + 1;
        else if (ids[mid] > fileDataId) high = mid - 1;
        else {
          const pos = block.cKeysStart + mid * 16;
          entries.push({ cKey: this.data.subarray(pos, pos + 16), localeFlags: block.localeFlags, contentFlags: block.contentFlags });
          break;
        }
      }
    }
    return entries;
  }
}
