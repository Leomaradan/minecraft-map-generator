import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { strFromU8, unzipSync } from 'fflate';
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
