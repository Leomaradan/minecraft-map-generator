/** A chunk of a WoW chunked file (WDT, ADT): 4-char id (stored reversed), size, data. */
export interface FileChunk {
  id: string;
  /** Start of the chunk data in the file buffer. */
  start: number;
  size: number;
}

/** Lists the chunks of `data` between `start` and `end`. */
export function readChunks(data: Buffer, start = 0, end = data.length): FileChunk[] {
  const chunks: FileChunk[] = [];
  let pos = start;
  while (pos + 8 <= end) {
    const id = data.toString('latin1', pos, pos + 4).split('').reverse().join('');
    const size = data.readUInt32LE(pos + 4);
    chunks.push({ id, start: pos + 8, size });
    pos += 8 + size;
  }
  return chunks;
}
