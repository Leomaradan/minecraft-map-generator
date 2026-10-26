import { inflateSync } from 'node:zlib';

/** Thrown when a BLTE block is encrypted with a key we don't have. */
export class EncryptedError extends Error {
  readonly keyName: string;

  constructor(keyName: string) {
    super(`content is encrypted with unknown key ${keyName}`);
    this.keyName = keyName;
  }
}

/**
 * Decodes a BLTE container (the encoding of every file in CASC storage).
 * Supports plain ('N') and zlib ('Z') blocks. Encrypted ('E') blocks throw an
 * EncryptedError, unless `zeroEncrypted` is set: they are then filled with zeros,
 * which suits DB2 files (their encrypted sections are flagged and skipped).
 */
export function decodeBlte(data: Buffer, zeroEncrypted = false): Buffer {
  if (data.readUInt32BE(0) !== 0x424c5445) {
    throw new Error('invalid BLTE magic');
  }
  const headerSize = data.readUInt32BE(4);
  if (headerSize === 0) {
    return decodeBlock(data.subarray(8));
  }

  const tableFormat = data[8];
  if (tableFormat !== 0x0f) {
    throw new Error(`unsupported BLTE table format ${tableFormat}`);
  }
  const blockCount = data.readUIntBE(9, 3);
  const blocks: Buffer[] = [];
  let infoOffset = 12;
  let dataOffset = headerSize;
  for (let i = 0; i < blockCount; i++) {
    const compressedSize = data.readUInt32BE(infoOffset);
    const decompressedSize = data.readUInt32BE(infoOffset + 4);
    let block: Buffer;
    try {
      block = decodeBlock(data.subarray(dataOffset, dataOffset + compressedSize));
    } catch (error) {
      if (!(zeroEncrypted && error instanceof EncryptedError)) throw error;
      block = Buffer.alloc(decompressedSize);
    }
    if (block.length !== decompressedSize) {
      throw new Error(`BLTE block ${i}: expected ${decompressedSize} bytes, got ${block.length}`);
    }
    blocks.push(block);
    infoOffset += 24;
    dataOffset += compressedSize;
  }
  return blocks.length === 1 ? blocks[0] : Buffer.concat(blocks);
}

function decodeBlock(block: Buffer): Buffer {
  const mode = String.fromCharCode(block[0]);
  switch (mode) {
    case 'N':
      return block.subarray(1);
    case 'Z':
      return inflateSync(block.subarray(1));
    case 'E': {
      const keyNameSize = block[1];
      const keyName = Buffer.from(block.subarray(2, 2 + keyNameSize)).reverse().toString('hex').toUpperCase();
      throw new EncryptedError(keyName);
    }
    default:
      throw new Error(`unsupported BLTE block mode '${mode}'`);
  }
}
