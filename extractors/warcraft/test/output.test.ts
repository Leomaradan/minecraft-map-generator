import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { crc32, inflateRawSync, inflateSync } from 'node:zlib';
import { biomeForArea, climateOf, waterBiome } from '../src/biomes.ts';
import type { AreaInfo } from '../src/gameData.ts';
import { encodePng } from '../src/output/png.ts';
import { ZipWriter } from '../src/output/zip.ts';

/** Minimal PNG decoder for filter types 0 and 1. */
function decodePng(png: Buffer) {
  let pos = 8;
  let width = 0;
  let height = 0;
  let bitDepth = 0;
  let colorType = 0;
  const idat: Buffer[] = [];
  while (pos < png.length) {
    const length = png.readUInt32BE(pos);
    const type = png.toString('latin1', pos + 4, pos + 8);
    const data = png.subarray(pos + 8, pos + 8 + length);
    assert.equal(png.readUInt32BE(pos + 8 + length), crc32(data, crc32(Buffer.from(type, 'latin1'))) >>> 0, `${type} CRC`);
    if (type === 'IHDR') [width, height, bitDepth, colorType] = [data.readUInt32BE(0), data.readUInt32BE(4), data[8], data[9]];
    if (type === 'IDAT') idat.push(data);
    pos += 12 + length;
  }
  const bpp = (bitDepth / 8) * (colorType === 2 ? 3 : 1);
  const raw = inflateSync(Buffer.concat(idat));
  const rowBytes = width * bpp;
  const rows: Buffer[] = [];
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (rowBytes + 1)];
    const row = Buffer.from(raw.subarray(y * (rowBytes + 1) + 1, (y + 1) * (rowBytes + 1)));
    if (filter === 1) for (let b = bpp; b < rowBytes; b++) row[b] = (row[b] + row[b - bpp]) & 0xff;
    rows.push(row);
  }
  return { width, height, bitDepth, colorType, rows };
}

test('16-bit grayscale PNG round-trips', async () => {
  const value = (x: number, y: number) => (x * 4099 + y * 7919) % 65536;
  const png = await encodePng(37, 23, 'gray16', (y, row) => {
    for (let x = 0; x < 37; x++) row.writeUInt16BE(value(x, y), x * 2);
  });
  const decoded = decodePng(png);
  assert.deepEqual([decoded.width, decoded.height, decoded.bitDepth, decoded.colorType], [37, 23, 16, 0]);
  for (let y = 0; y < 23; y++) for (let x = 0; x < 37; x++) assert.equal(decoded.rows[y].readUInt16BE(x * 2), value(x, y));
});

test('RGB PNG round-trips', async () => {
  const png = await encodePng(5, 3, 'rgb8', (y, row) => {
    for (let x = 0; x < 5; x++) row.set([x * 50, y * 100, 255 - x], x * 3);
  });
  const decoded = decodePng(png);
  assert.deepEqual([...decoded.rows[2].subarray(12, 15)], [200, 200, 251]);
});

test('ZIP entries can be read back', () => {
  const dir = mkdtempSync(join(tmpdir(), 'wme-'));
  try {
    const path = join(dir, 'test.zip');
    const zip = new ZipWriter(path);
    zip.add('map.json', Buffer.from('{"name":"Élwynn"}'));
    zip.add('heightmap.png', Buffer.from('not really a png'));
    zip.close();

    const data = readFileSync(path);
    const end = data.length - 22;
    assert.equal(data.readUInt32LE(end), 0x06054b50);
    assert.equal(data.readUInt16LE(end + 10), 2);
    let central = data.readUInt32LE(end + 16);
    const entries = new Map<string, Buffer>();
    for (let i = 0; i < 2; i++) {
      const nameLength = data.readUInt16LE(central + 28);
      const name = data.toString('utf8', central + 46, central + 46 + nameLength);
      const local = data.readUInt32LE(central + 42);
      const method = data.readUInt16LE(local + 8);
      const size = data.readUInt32LE(local + 18);
      const body = data.subarray(local + 30 + data.readUInt16LE(local + 26), local + 30 + data.readUInt16LE(local + 26) + size);
      const content = method === 8 ? inflateRawSync(body) : body;
      assert.equal(crc32(content) >>> 0, data.readUInt32LE(central + 16));
      entries.set(name, content);
      central += 46 + nameLength;
    }
    assert.equal(entries.get('map.json')?.toString(), '{"name":"Élwynn"}');
    assert.equal(entries.get('heightmap.png')?.toString(), 'not really a png');
  } finally {
    rmSync(dir, { recursive: true });
  }
});

const area = (id: number, zoneName: string, parentId = 0): AreaInfo => ({ id, zoneName, name: zoneName, parentId, mapId: 0 });

test('biomes come from overrides, then zones, then keywords', () => {
  const goldshire = [area(87, 'Goldshire', 12), area(12, 'ElwynnForest')];
  assert.equal(biomeForArea(goldshire), 'minecraft:forest');
  assert.equal(biomeForArea(goldshire, { goldshire: 'meadow' }), 'minecraft:meadow');
  assert.equal(biomeForArea(goldshire, { 12: 'minecraft:cherry_grove' }), 'minecraft:cherry_grove');
  assert.equal(biomeForArea([area(9001, 'FrostwhisperGorge')]), 'minecraft:snowy_taiga');
  assert.equal(biomeForArea([area(9002, 'Nowhere')]), 'minecraft:plains');
  assert.deepEqual(biomeForArea([area(1, 'X')], { x: { biome: 'desert', top: 'minecraft:red_sand' } }), {
    biome: 'minecraft:desert',
    top: 'minecraft:red_sand',
  });
});

test('water biomes follow the climate', () => {
  assert.equal(climateOf('minecraft:snowy_taiga'), 'frozen');
  assert.equal(climateOf('minecraft:desert'), 'warm');
  assert.equal(waterBiome('ocean', 'frozen', true), 'minecraft:deep_frozen_ocean');
  assert.equal(waterBiome('river', 'warm', false), 'minecraft:river');
  assert.equal(waterBiome('ocean', 'temperate', false), 'minecraft:ocean');
});

test('distance to the edge of a shape', async () => {
  const { distanceToEdge } = await import('../src/mapPack.ts');
  // A 7x7 square filling a 9x9 image.
  const d = distanceToEdge(9, 9, (i) => i % 9 >= 1 && i % 9 <= 7 && Math.floor(i / 9) >= 1 && Math.floor(i / 9) <= 7);
  assert.equal(d[0], 0);
  assert.equal(d[1 * 9 + 1], 1);
  assert.equal(d[4 * 9 + 4], 4);
  assert.equal(d[4 * 9 + 2], 2);
});

test('structure NBT has the template layout', async () => {
  const { structureNbt, gzipNbt, DATA_VERSION } = await import('../src/output/nbt.ts');
  const { gunzipSync } = await import('node:zlib');
  const nbt = gunzipSync(gzipNbt(structureNbt([2, 1, 1], ['minecraft:stone', 'minecraft:oak_planks'], [{ x: 0, y: 0, z: 0, state: 0 }, { x: 1, y: 0, z: 0, state: 1 }])));
  // Root compound with an empty name, then DataVersion.
  assert.equal(nbt[0], 10);
  assert.equal(nbt.readUInt16BE(1), 0);
  assert.equal(nbt[3], 3);
  assert.equal(nbt.toString('utf8', 6, 17), 'DataVersion');
  assert.equal(nbt.readInt32BE(17), DATA_VERSION);
  for (const key of ['size', 'palette', 'blocks', 'pos', 'state', 'entities', 'minecraft:oak_planks']) assert.ok(nbt.includes(key), key);
  assert.equal(nbt[nbt.length - 1], 0);
});

test('BLP average color of a DXT1 texture', async () => {
  const { blpAverageColor } = await import('../src/formats/blp.ts');
  const header = Buffer.alloc(1172);
  header.write('BLP2', 0, 'latin1');
  header.writeUInt32LE(1, 4);
  header[8] = 2; // DXT
  header[10] = 0; // DXT1
  header.writeUInt32LE(4, 12);
  header.writeUInt32LE(4, 16);
  header.writeUInt32LE(1172, 20);
  header.writeUInt32LE(8, 84);
  // One block: color0 pure red, color1 pure blue, every pixel index 0 except the first row (index 1).
  const block = Buffer.alloc(8);
  block.writeUInt16LE(0xf800, 0);
  block.writeUInt16LE(0x001f, 2);
  block.writeUInt32LE(0b01010101, 4);
  const color = blpAverageColor(Buffer.concat([header, block]));
  assert.ok(color);
  assert.ok(Math.abs(color.r - 191.25) < 0.01 && color.g === 0 && Math.abs(color.b - 63.75) < 0.01);
});

test('nearest block by color', async () => {
  const { nearestBlock } = await import('../src/palette.ts');
  assert.equal(nearestBlock({ r: 250, g: 254, b: 254 }), 'minecraft:snow_block');
  assert.equal(nearestBlock({ r: 160, g: 130, b: 80 }), 'minecraft:oak_planks');
  assert.equal(nearestBlock({ r: 40, g: 46, b: 140 }), 'minecraft:blue_concrete');
});
