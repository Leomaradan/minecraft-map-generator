import { readChunks } from './chunks.ts';

export interface WdtTile {
  x: number;
  y: number;
  /** FileDataID of the root ADT (heights, liquids, area IDs). */
  rootAdt: number;
  /** FileDataID of the obj0 ADT (model placements), 0 if none. */
  obj0Adt: number;
}

/** Reads the tiles of a WDT (MAIN flags + MAID FileDataIDs, 64x64, row-major by y). */
export function readWdt(data: Buffer): WdtTile[] {
  const chunks = readChunks(data);
  const main = chunks.find((c) => c.id === 'MAIN');
  const maid = chunks.find((c) => c.id === 'MAID');
  if (!maid) return []; // WMO-only map (most dungeons): no terrain
  const tiles: WdtTile[] = [];
  for (let y = 0; y < 64; y++) {
    for (let x = 0; x < 64; x++) {
      const index = y * 64 + x;
      const hasTerrain = main ? (data.readUInt32LE(main.start + index * 8) & 1) !== 0 : true;
      const rootAdt = data.readUInt32LE(maid.start + index * 32);
      const obj0Adt = data.readUInt32LE(maid.start + index * 32 + 4);
      if (hasTerrain && rootAdt !== 0) tiles.push({ x, y, rootAdt, obj0Adt });
    }
  }
  return tiles;
}
