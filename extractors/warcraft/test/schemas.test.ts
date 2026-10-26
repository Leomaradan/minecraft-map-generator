import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { inflateRawSync } from 'node:zlib';
import { Ajv2020 } from 'ajv/dist/2020.js';
import type { GameData, MapInfo } from '../src/gameData.ts';
import { writeMapPack, type MapType, type PackOptions } from '../src/mapPack.ts';
import { gzipNbt, structureNbt } from '../src/output/nbt.ts';
import { TerrainRaster } from '../src/terrain.ts';

// The map ZIPs must follow the format of this repository: docs/schemas.
const SCHEMAS = new URL('../../../docs/schemas/', import.meta.url);
const ajv = new Ajv2020({ allErrors: true, strict: false });
const validators = Object.fromEntries(
  ['map', 'biomes', 'structures'].map((name) => [name, ajv.compile(JSON.parse(readFileSync(new URL(`${name}.schema.json`, SCHEMAS), 'utf8')))]),
);

const game = {
  areaChain: (id: number) => (id ? [{ id, zoneName: 'ElwynnForest', name: 'Forêt d’Elwynn', parentId: 0, mapId: 0 }] : []),
} as unknown as GameData;
const map: MapInfo = { id: 0, directory: 'Azeroth', name: 'Eastern Kingdoms', wdtFileDataId: 0, instanceType: 0, expansion: 0 };

/** A 32x32 hill with a lake, in quarter-yards. */
function terrain(): TerrainRaster {
  const raster = new TerrainRaster({ x0: 0, y0: 0, x1: 64, y1: 64 }, 2);
  const area = raster.areaIndexOf(12);
  for (let y = 0; y < 32; y++) {
    for (let x = 0; x < 32; x++) {
      const i = y * 32 + x;
      raster.ground[i] = Math.round((40 - Math.hypot(x - 16, y - 16)) * 4);
      raster.area[i] = area;
      if (x < 8) {
        raster.ground[i] = -40;
        raster.fluid[i] = 0;
        raster.fluidKind[i] = 1;
      }
    }
  }
  return raster;
}

function readZip(path: string): Map<string, Buffer> {
  const data = readFileSync(path);
  const end = data.length - 22;
  let central = data.readUInt32LE(end + 16);
  const entries = new Map<string, Buffer>();
  for (let n = data.readUInt16LE(end + 10); n > 0; n--) {
    const nameLength = data.readUInt16LE(central + 28);
    const name = data.toString('utf8', central + 46, central + 46 + nameLength);
    const local = data.readUInt32LE(central + 42);
    const body = local + 30 + data.readUInt16LE(local + 26);
    const content = data.subarray(body, body + data.readUInt32LE(local + 18));
    entries.set(name, data.readUInt16LE(local + 8) === 8 ? inflateRawSync(content) : content);
    central += 46 + nameLength;
  }
  return entries;
}

async function pack(type: MapType, raster: TerrainRaster, extra: Partial<PackOptions> = {}): Promise<Map<string, Buffer>> {
  const dir = mkdtempSync(join(tmpdir(), 'wme-schema-'));
  try {
    const path = join(dir, 'map.zip');
    await writeMapPack(path, raster, game, map, {
      name: 'Test',
      type,
      verticalScale: 2,
      seaLevel: 63,
      minHeight: -256,
      missing: 'ocean',
      oceanDepth: 32,
      biomeOverrides: { 12: { biome: 'minecraft:desert', top: 'minecraft:red_sand' } },
      vanillaStructures: false,
      carvers: false,
      ...extra,
    }, {});
    return readZip(path);
  } finally {
    rmSync(dir, { recursive: true });
  }
}

function assertValid(entries: Map<string, Buffer>): void {
  for (const [name, validate] of Object.entries(validators)) {
    const file = entries.get(`${name}.json`);
    if (!file) continue;
    const ok = validate(JSON.parse(file.toString('utf8')));
    assert.ok(ok, `${name}.json: ${JSON.stringify(validate.errors)}`);
  }
}

test('a normal map with structures follows the schemas', async () => {
  const data = gzipNbt(structureNbt([1, 2, 1], ['minecraft:stone_bricks'], [{ x: 0, y: 0, z: 0, state: 0 }, { x: 0, y: 1, z: 0, state: 0 }]));
  const entries = await pack('normal', terrain(), {
    structures: {
      files: [{ path: 'structures/wmo1_test.nbt', data }],
      placements: [{ path: 'structures/wmo1_test.nbt', x: -2, y: 80, z: 3 }],
      buildings: 1,
      blocks: 2,
      bytes: data.length,
      skipped: 0,
    },
  });
  for (const file of ['map.json', 'heightmap.png', 'water.png', 'biomes.png', 'biomes.json', 'structures.json', 'structures/wmo1_test.nbt']) {
    assert.ok(entries.has(file), file);
  }
  assertValid(entries);
});

test('a floating map follows the schemas', async () => {
  const raster = terrain();
  raster.ground.fill(-32768); // terrain made of holes
  const { top, bottom } = raster.modelLayers();
  for (let i = 0; i < 100; i++) {
    top[i + 400] = 800;
    bottom[i + 400] = 760;
  }
  const entries = await pack('floating', raster);
  assert.ok(entries.has('bottom.png'));
  assertValid(entries);
});

test('an underground map follows the schemas', async () => {
  const raster = terrain();
  const { ceiling } = raster.modelLayers();
  ceiling.fill(1200, 0, 600);
  const entries = await pack('underground', raster);
  assert.ok(entries.has('ceiling.png'));
  assertValid(entries);
});
