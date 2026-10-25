import assert from 'node:assert/strict';
import { test } from 'node:test';
import { deflateSync } from 'node:zlib';
import { EncryptedError, decodeBlte } from '../src/casc/blte.ts';
import { terrainHeight } from '../src/formats/adt.ts';
import { parseDbd } from '../src/formats/dbd.ts';

function blte(blocks: Buffer[]): Buffer {
  const headerSize = 12 + blocks.length * 24;
  const header = Buffer.alloc(headerSize);
  header.write('BLTE', 0, 'latin1');
  header.writeUInt32BE(headerSize, 4);
  header[8] = 0x0f;
  header.writeUIntBE(blocks.length, 9, 3);
  return Buffer.concat([header, ...blocks]);
}

function setSizes(file: Buffer, sizes: [number, number][]): Buffer {
  sizes.forEach(([compressed, decompressed], i) => {
    file.writeUInt32BE(compressed, 12 + i * 24);
    file.writeUInt32BE(decompressed, 16 + i * 24);
  });
  return file;
}

test('BLTE decodes plain and zlib blocks', () => {
  const plain = Buffer.concat([Buffer.from('N'), Buffer.from('hello ')]);
  const zlib = Buffer.concat([Buffer.from('Z'), deflateSync(Buffer.from('world'))]);
  const file = setSizes(blte([plain, zlib]), [[plain.length, 6], [zlib.length, 5]]);
  assert.equal(decodeBlte(file).toString(), 'hello world');
});

test('BLTE decodes a single block without a table', () => {
  const file = Buffer.concat([Buffer.from('BLTE'), Buffer.alloc(4), Buffer.from('Nraw')]);
  assert.equal(decodeBlte(file).toString(), 'raw');
});

test('BLTE encrypted blocks throw, or become zeros on request', () => {
  const encrypted = Buffer.concat([Buffer.from('E'), Buffer.from([8]), Buffer.from('0123456789abcdef', 'hex'), Buffer.alloc(8)]);
  const file = setSizes(blte([encrypted]), [[encrypted.length, 4]]);
  assert.throws(() => decodeBlte(file), (error: unknown) => error instanceof EncryptedError && error.keyName === 'EFCDAB8967452301');
  assert.deepEqual(decodeBlte(file, true), Buffer.alloc(4));
});

test('terrain height matches the vertices and is continuous', () => {
  const heights = new Float32Array(145);
  for (let i = 0; i < 145; i++) heights[i] = (i * 37) % 11;
  const outer = (row: number, col: number) => heights[row * 17 + col];
  const inner = (row: number, col: number) => heights[row * 17 + 9 + col];
  const close = (a: number, b: number) => assert.ok(Math.abs(a - b) < 1e-4, `${a} != ${b}`);

  close(terrainHeight(heights, 2, 3, 0, 0), outer(3, 2));
  close(terrainHeight(heights, 2, 3, 0.999999, 0), outer(3, 3));
  close(terrainHeight(heights, 2, 3, 0, 0.999999), outer(4, 2));
  close(terrainHeight(heights, 2, 3, 0.5, 0.5), inner(3, 2));
  // Edges are linear between the corners.
  close(terrainHeight(heights, 2, 3, 0.25, 0), outer(3, 2) * 0.75 + outer(3, 3) * 0.25);
  // Continuity across the diagonals between triangles.
  for (const t of [0.1, 0.3, 0.45]) {
    close(terrainHeight(heights, 1, 1, t, t - 1e-9), terrainHeight(heights, 1, 1, t + 1e-9, t));
    close(terrainHeight(heights, 1, 1, t, 1 - t - 1e-9), terrainHeight(heights, 1, 1, t, 1 - t + 1e-9));
  }
});

test('DBD parsing picks the layout and its inline columns', () => {
  const text = [
    'COLUMNS',
    'int ID',
    'string Directory',
    'locstring MapName_lang',
    'int<Map::ID> ParentMapID',
    'float Scale',
    'int Flags',
    '',
    'LAYOUT 11111111',
    'BUILD 1.0.0.1',
    '$id$ID<32>',
    'Directory',
    '',
    'LAYOUT AAAA0000, BBBB1111',
    'BUILD 12.0.0.1',
    '$noninline,id$ID<32>',
    'Directory',
    'MapName_lang',
    'ParentMapID<u16>',
    'Scale',
    'Flags<32>[3] // comment',
  ].join('\n');
  const fields = parseDbd(text, 'BBBB1111');
  assert.ok(fields);
  assert.deepEqual(fields.map((f) => f.name), ['ID', 'Directory', 'MapName_lang', 'ParentMapID', 'Scale', 'Flags']);
  assert.equal(fields[0].nonInline, true);
  assert.equal(fields[3].size, 16);
  assert.equal(fields[3].signed, false);
  assert.equal(fields[5].arrayLength, 3);
  assert.equal(fields[2].type, 'locstring');
  assert.equal(parseDbd(text, 'CCCC2222'), undefined);
});

test('low-resolution holes cover 2x2 cells', async () => {
  const { isHole } = await import('../src/formats/adt.ts');
  const { readAdt } = await import('../src/formats/adt.ts');
  // One MCNK with the low-resolution hole bit 5 (row 1, col 1 of the 4x4 grid).
  const mcnk = Buffer.alloc(128);
  mcnk.writeUInt16LE(1 << 5, 0x3c);
  const header = Buffer.alloc(8);
  header.write('KNCM', 0, 'latin1');
  header.writeUInt32LE(mcnk.length, 4);
  const chunk = readAdt(Buffer.concat([header, mcnk])).chunks[0];
  assert.ok(chunk);
  const holes = [];
  for (let row = 0; row < 8; row++) for (let col = 0; col < 8; col++) if (isHole(chunk, col, row)) holes.push(`${col},${row}`);
  assert.deepEqual(holes, ['2,2', '3,2', '2,3', '3,3']);
});

test('WMO placement maps model x to map y and model y to map x', async () => {
  const { placementTransform } = await import('../src/formats/wmo.ts');
  const base = { fileDataId: 1, uniqueId: 1, extents: [0, 0, 0, 0, 0, 0] as [number, number, number, number, number, number] };
  const flat = placementTransform({ ...base, position: [100, 50, 200], rotation: [0, 0, 0], scale: 2 });
  assert.deepEqual(flat(1, 2, 3), [104, 202, 56]);
  // A 90-degree yaw (rotation y) turns model x into model y.
  const turned = placementTransform({ ...base, position: [0, 0, 0], rotation: [0, 90, 0], scale: 1 });
  const [x, y, h] = turned(1, 0, 0);
  assert.ok(Math.abs(x - 1) < 1e-9 && Math.abs(y) < 1e-9 && Math.abs(h) < 1e-9);
});
