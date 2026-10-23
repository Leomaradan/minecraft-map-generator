import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { strFromU8, strToU8, unzipSync, zipSync } from 'fflate';
import { describe, expect, it } from 'vitest';
import { exportZip, importZip } from './zipIO';

const example = (name: string) => new Uint8Array(readFileSync(resolve(__dirname, '../../../mod/example', name)));

describe.each(['example.zip', 'example-tall.zip'])('zip round trip: %s', (name) => {
  it('keeps the maps through import and export', () => {
    const first = importZip(example(name));
    const exported = exportZip(first.project);
    const second = importZip(exported);
    const a = first.project;
    const b = second.project;
    expect(b.heightmap!.data).toEqual(a.heightmap!.data);
    expect(b.fluidRaw).toEqual(a.fluidRaw);
    const colorOf = (p: typeof a) => Array.from(p.biomeIndex, (id) => p.biomes.find((e) => e.id === id)?.color);
    expect(colorOf(b)).toEqual(colorOf(a));
    expect(b.instances.length).toBe(a.instances.length);
    const original = unzipSync(example(name));
    const files = unzipSync(exported);
    for (const f of ['water.png', 'lava.png']) {
      if (original[f]) expect(files[f]).toBeDefined();
    }
    expect(Object.keys(JSON.parse(strFromU8(files['map.json'])))).toContain('$schema');
    expect(files['editor.json']).toBeDefined();
  });
});

describe('editor-only data', () => {
  it('keeps several reference images and hidden biomes', () => {
    const { project } = importZip(example('example.zip'));
    project.references = [
      { id: 900, name: 'Satellite', bytes: new Uint8Array([1, 2, 3]), mime: 'image/png', opacity: 0.3, visible: true },
      { id: 901, name: 'Sketch', bytes: new Uint8Array([4, 5]), mime: 'image/jpeg', opacity: 0.8, visible: false },
    ];
    project.biomes[0].hidden = true;
    const color = project.biomes[0].color;
    const back = importZip(exportZip(project)).project;
    expect(back.references.map((r) => [r.name, r.opacity, r.visible, r.mime])).toEqual([
      ['Satellite', 0.3, true, 'image/png'],
      ['Sketch', 0.8, false, 'image/jpeg'],
    ]);
    expect(Array.from(back.references[1].bytes)).toEqual([4, 5]);
    expect(back.biomes.filter((b) => b.hidden).map((b) => b.color)).toEqual([color]);
  });

  it('does not put hidden biomes in biomes.json', () => {
    const { project } = importZip(example('example.zip'));
    project.biomes[0].hidden = true;
    const files = unzipSync(exportZip(project));
    expect(Object.keys(JSON.parse(strFromU8(files['biomes.json']))).length).toBe(project.biomes.length + 1);
  });
});

describe('dimensions', () => {
  it('refuses a ZIP with one map per dimension', () => {
    expect(() => importZip(example('example-dimensions.zip'))).toThrow(/one map per dimension/);
  });
});

describe('bottom and ceiling maps', () => {
  it('keeps them through import and export', () => {
    const files = unzipSync(example('example.zip'));
    const config = JSON.parse(strFromU8(files['map.json']));
    config.files = { ceiling: 'maps/roof.png', water: null };
    files['map.json'] = strToU8(JSON.stringify(config));
    files['bottom.png'] = files['heightmap.png'];
    files['maps/roof.png'] = files['heightmap.png'];
    const exported = unzipSync(exportZip(importZip(zipSync(files)).project));
    expect(exported['bottom.png']).toEqual(files['heightmap.png']);
    expect(exported['ceiling.png']).toEqual(files['heightmap.png']);
    expect(exported['water.png']).toBeUndefined();
  });
});

describe('fluid layers', () => {
  it('imports the water and lava maps as read-only layers without editor.json', () => {
    const { project } = importZip(example('example.zip'));
    expect(project.bodies.map((b) => [b.fluid, b.mode])).toEqual([['water', 'raw'], ['lava', 'raw']]);
  });

  it('gives old flat lakes their exported level as surface', () => {
    const { project } = importZip(example('example.zip'));
    const files = unzipSync(exportZip(project));
    const editor = JSON.parse(strFromU8(files['editor.json']));
    // An older editor saved the lakes without a surface Y
    editor.bodies = editor.bodies.map((b: object) => ({ ...b, mode: 'lake', levelOverride: 12 }));
    files['editor.json'] = strToU8(JSON.stringify(editor));
    const back = importZip(zipSync(files)).project;
    const water = back.bodies.find((b) => b.fluid === 'water')!;
    expect(water.mode).toBe('lake');
    expect(water.level).toBeGreaterThan(0);
    expect('levelOverride' in water).toBe(false);
  });
});

describe('free flows', () => {
  it('reads the painted rivers of an older editor as free flows, and keeps rivers made of nodes', () => {
    const { project } = importZip(example('example.zip'));
    project.bodies = [
      { ...project.bodies[0], mode: 'river' },
      { id: 990, name: 'River', fluid: 'water', mode: 'river', offset: 0, nodes: [{ id: 1, x: 3, z: 3, level: 70 }], edges: [] },
    ];
    const back = importZip(exportZip(project)).project;
    expect(back.bodies.map((b) => b.mode)).toEqual(['flow', 'river']);
    expect(back.bodies[1].nodes).toEqual([{ id: 1, x: 3, z: 3, level: 70 }]);
  });
});
