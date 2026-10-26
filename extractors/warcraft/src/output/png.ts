import { createDeflate, crc32 } from 'node:zlib';

export type PngKind = 'gray8' | 'gray16' | 'rgb8';

const KINDS: Record<PngKind, { bitDepth: number; colorType: number; bytesPerPixel: number }> = {
  gray8: { bitDepth: 8, colorType: 0, bytesPerPixel: 1 },
  gray16: { bitDepth: 16, colorType: 0, bytesPerPixel: 2 },
  rgb8: { bitDepth: 8, colorType: 2, bytesPerPixel: 3 },
};

/**
 * Encodes a PNG, asking `fillRow` for the raw samples of each row (big-endian
 * for 16-bit). Rows are filtered (Sub) and compressed as they come, so the whole
 * raw image is never held in memory.
 */
export async function encodePng(
  width: number,
  height: number,
  kind: PngKind,
  fillRow: (y: number, row: Buffer) => void,
): Promise<Buffer> {
  const { bitDepth, colorType, bytesPerPixel } = KINDS[kind];
  const rowBytes = width * bytesPerPixel;
  const deflate = createDeflate({ level: 6, memLevel: 9 });
  const compressed: Buffer[] = [];
  deflate.on('data', (chunk: Buffer) => compressed.push(chunk));
  const finished = new Promise<void>((resolve, reject) => {
    deflate.on('end', resolve);
    deflate.on('error', reject);
  });

  const raw = Buffer.alloc(rowBytes);
  const rowsPerBatch = Math.max(1, Math.floor((1 << 20) / (rowBytes + 1)));
  for (let y = 0; y < height; y += rowsPerBatch) {
    const count = Math.min(rowsPerBatch, height - y);
    const batch = Buffer.alloc(count * (rowBytes + 1));
    for (let r = 0; r < count; r++) {
      raw.fill(0);
      fillRow(y + r, raw);
      const out = r * (rowBytes + 1);
      batch[out] = 1; // filter type Sub
      for (let b = 0; b < rowBytes; b++) {
        const left = b >= bytesPerPixel ? raw[b - bytesPerPixel] : 0;
        batch[out + 1 + b] = (raw[b] - left) & 0xff;
      }
    }
    if (!deflate.write(batch)) await new Promise((resolve) => deflate.once('drain', resolve));
  }
  deflate.end();
  await finished;

  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = bitDepth;
  header[9] = colorType;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', header),
    pngChunk('IDAT', Buffer.concat(compressed)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

function pngChunk(type: string, data: Buffer): Buffer {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(data.length, 0);
  head.write(type, 4, 'latin1');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(data, crc32(head.subarray(4))) >>> 0);
  return Buffer.concat([head, data, crc]);
}
