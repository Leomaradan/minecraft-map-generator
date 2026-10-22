import { useRef, useState } from 'react';
import { resolveHeightMapping, toY } from '../../lib/heightMapping';
import type { Json, JsonObject } from '../../lib/project';
import { MapView } from '../MapView';
import { useStore } from '../store';
import { BlockDatalist, BlockListEditor, CarversEditor, FeaturesEditor, Field, NumInput, Section, SnowEditor, TextInput } from '../ui';

async function readBytes(file: File): Promise<Uint8Array> {
  return new Uint8Array(await file.arrayBuffer());
}

function obj(v: Json | undefined): JsonObject {
  return typeof v === 'object' && v !== null && !Array.isArray(v) ? v : {};
}

/** Sets a key of a nested config object, removing the object when it becomes empty. */
function useNested(key: string) {
  const config = useStore((s) => s.project.config);
  const setConfig = useStore((s) => s.setConfig);
  const current = obj(config[key]);
  return {
    current,
    set: (k: string, v: Json | undefined) => {
      const next = { ...current };
      if (v === undefined) delete next[k];
      else next[k] = v;
      setConfig(key, Object.keys(next).length > 0 ? next : undefined);
    },
  };
}

function GeneralPanel() {
  const project = useStore((s) => s.project);
  const config = project.config;
  const setConfig = useStore((s) => s.setConfig);
  const loadHeightmap = useStore((s) => s.loadHeightmap);
  const addReference = useStore((s) => s.addReference);
  const updateReference = useStore((s) => s.updateReference);
  const removeReference = useStore((s) => s.removeReference);
  const [error, setError] = useState<string | null>(null);
  const [notes, setNotes] = useState<string[]>([]);
  const hmInput = useRef<HTMLInputElement>(null);

  const height = useNested('height');
  const dimension = useNested('dimension');
  const outside = useNested('outside');
  const spawn = useNested('spawn');
  const blocks = useNested('blocks');
  const decoration = useNested('decoration');
  const vanilla = useNested('vanilla_structures');
  const hm = project.heightmap;
  const rangeMode = height.current.min_y !== undefined || height.current.max_y !== undefined;
  const mapping = resolveHeightMapping(config.height as never, hm?.bitDepth ?? 8);
  const num = (k: string) => config[k] as number | undefined;
  const origin = config.origin as number[] | undefined;
  const vanillaOff = config.vanilla_structures === false;

  const onHeightmap = async (file: File) => {
    setError(null);
    try {
      const bytes = await readBytes(file);
      const old = project.heightmap;
      const probe = hm ? 'replace' : 'new';
      if (probe === 'replace' && old && (project.bodies.length > 0 || project.biomeIndex.some((v) => v > 0))) {
        // size changes reset the paintings: ask before throwing them away
        if (!confirm('If the new heightmap has another size, the painted fluids, biomes and structures are reset. Continue?')) return;
      }
      setNotes(loadHeightmap(bytes));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <div>
      <h2>General</h2>
      <Section title="Heightmap" open>
        <input ref={hmInput} type="file" accept="image/png" hidden onChange={(e) => e.target.files?.[0] && onHeightmap(e.target.files[0])} />
        <button type="button" onClick={() => hmInput.current?.click()}>{hm ? 'Replace heightmap…' : 'Upload heightmap…'}</button>
        {hm && <p className="ok">{hm.width}×{hm.height}, {hm.bitDepth}-bit grayscale. Y range {toY(mapping, 0).toFixed(1)} to {toY(mapping, hm.bitDepth === 16 ? 65535 : 255).toFixed(1)}</p>}
        {error && <p className="err">{error}</p>}
        {notes.map((n) => <p className="warn" key={n}>{n}</p>)}
      </Section>

      <Section title="Reference images" open>
        <input
          type="file"
          accept="image/png,image/jpeg"
          multiple
          onChange={async (e) => {
            for (const f of Array.from(e.target.files ?? [])) addReference(f.name, await readBytes(f), f.type);
            e.target.value = '';
          }}
        />
        {project.references.map((r) => (
          <div className="card" key={r.id}>
            <div className="row">
              <label><input type="checkbox" checked={r.visible} onChange={(e) => updateReference(r.id, { visible: e.target.checked })} /> </label>
              <input type="text" value={r.name} onChange={(e) => updateReference(r.id, { name: e.target.value })} />
              <button type="button" className="danger" onClick={() => removeReference(r.id)}>×</button>
            </div>
            <Field label={`Opacity ${Math.round(r.opacity * 100)}%`}>
              <input type="range" min={0} max={1} step={0.05} value={r.opacity} onChange={(e) => updateReference(r.id, { opacity: Number(e.target.value) })} />
            </Field>
          </div>
        ))}
        <p className="hint">The images are stretched over the heightmap, stacked in this order, and shown under the painted layers. They are stored in the ZIP for the editor only.</p>
      </Section>

      <Section title="Map" open>
        <Field label="Name"><TextInput value={config.name as string | undefined} onChange={(v) => setConfig('name', v)} /></Field>
        <Field label="Description"><TextInput value={config.description as string | undefined} onChange={(v) => setConfig('description', v)} /></Field>
        <Field label="Author"><TextInput value={config.author as string | undefined} onChange={(v) => setConfig('author', v)} /></Field>
        <Field label="Horizontal scale (blocks per pixel)"><NumInput value={num('horizontal_scale')} min={1} onChange={(v) => setConfig('horizontal_scale', v)} placeholder="1" /></Field>
        <Field label="Origin X, Z (default: centered)">
          <div className="row">
            <NumInput value={origin?.[0]} onChange={(v) => setConfig('origin', v === undefined && origin?.[1] === undefined ? undefined : [v ?? 0, origin?.[1] ?? 0])} />
            <NumInput value={origin?.[1]} onChange={(v) => setConfig('origin', v === undefined && origin?.[0] === undefined ? undefined : [origin?.[0] ?? 0, v ?? 0])} />
          </div>
        </Field>
        <Field label="Sea level"><NumInput value={num('sea_level')} onChange={(v) => setConfig('sea_level', v)} placeholder="63" /></Field>
        <Field label="Biome transition (blocks)"><NumInput value={num('biome_transition')} min={0} max={64} onChange={(v) => setConfig('biome_transition', v)} placeholder="4" /></Field>
      </Section>

      <Section title="Height mapping">
        <Field label="Mode">
          <select value={rangeMode ? 'range' : 'linear'} onChange={(e) => setConfig('height', e.target.value === 'range' ? { min_y: 0, max_y: 255 } : undefined)}>
            <option value="linear">base Y + value × scale</option>
            <option value="range">min Y … max Y</option>
          </select>
        </Field>
        {rangeMode ? (
          <>
            <Field label="Min Y (value 0)"><NumInput value={height.current.min_y as number | undefined} onChange={(v) => height.set('min_y', v)} /></Field>
            <Field label="Max Y (max value)"><NumInput value={height.current.max_y as number | undefined} onChange={(v) => height.set('max_y', v)} /></Field>
          </>
        ) : (
          <>
            <Field label="Base Y"><NumInput value={height.current.base_y as number | undefined} onChange={(v) => height.set('base_y', v)} placeholder="0" /></Field>
            <Field label="Scale"><NumInput value={height.current.scale as number | undefined} step={0.01} onChange={(v) => height.set('scale', v)} placeholder="1" /></Field>
          </>
        )}
        <h4>World height</h4>
        <Field label="Min Y (multiple of 16)"><NumInput value={dimension.current.min_y as number | undefined} step={16} onChange={(v) => dimension.set('min_y', v)} placeholder="automatic" /></Field>
        <Field label="Height (multiple of 16)"><NumInput value={dimension.current.height as number | undefined} step={16} onChange={(v) => dimension.set('height', v)} placeholder="automatic" /></Field>
      </Section>

      <Section title="Outside the map">
        <Field label="Mode">
          <select value={(outside.current.mode as string | undefined) ?? 'flat'} onChange={(e) => outside.set('mode', e.target.value === 'flat' ? undefined : e.target.value)}>
            <option value="flat">flat</option>
            <option value="void">void</option>
            <option value="extend">extend</option>
          </select>
        </Field>
        <Field label="Ground level"><NumInput value={outside.current.ground_level as number | undefined} onChange={(v) => outside.set('ground_level', v)} placeholder="64" /></Field>
        <Field label="Water level"><NumInput value={outside.current.water_level as number | undefined} onChange={(v) => outside.set('water_level', v)} placeholder="63" /></Field>
        <Field label="Biome"><TextInput value={outside.current.biome as string | undefined} onChange={(v) => outside.set('biome', v)} list="biome-ids" placeholder="minecraft:plains" /></Field>
        <label><input type="checkbox" checked={outside.current.border !== false} onChange={(e) => outside.set('border', e.target.checked ? undefined : false)} /> World border around the map</label>
      </Section>

      <Section title="Spawn">
        <Field label="X"><NumInput value={spawn.current.x as number | undefined} onChange={(v) => spawn.set('x', v)} /></Field>
        <Field label="Z"><NumInput value={spawn.current.z as number | undefined} onChange={(v) => spawn.set('z', v)} /></Field>
        <Field label="Y (empty: on the surface)"><NumInput value={spawn.current.y as number | undefined} onChange={(v) => spawn.set('y', v)} /></Field>
        <p className="hint">World coordinates. Leave X and Z empty for the vanilla spawn.</p>
      </Section>

      <Section title="Blocks">
        <Field label="Under the surface layers"><BlockListEditor value={blocks.current.default} onChange={(v) => blocks.set('default', v)} /></Field>
        <label><input type="checkbox" checked={blocks.current.bedrock !== false} onChange={(e) => blocks.set('bedrock', e.target.checked ? undefined : false)} /> Bedrock at the bottom</label>
      </Section>

      <Section title="Snow, features and caves">
        <Field label="Snow and ice"><SnowEditor value={config.snow} onChange={(v) => setConfig('snow', v)} /></Field>
        <Field label="Biome features"><FeaturesEditor value={decoration.current.features} onChange={(v) => decoration.set('features', v)} /></Field>
        <Field label="Carvers"><CarversEditor value={decoration.current.carvers} onChange={(v) => decoration.set('carvers', v)} /></Field>
      </Section>

      <Section title="Vanilla structures">
        <label><input type="checkbox" checked={!vanillaOff && vanilla.current.enabled !== false} onChange={(e) => setConfig('vanilla_structures', e.target.checked ? undefined : false)} /> Generate villages, temples…</label>
        {!vanillaOff && vanilla.current.enabled !== false && (
          <>
            <Field label="Disabled (one per line)"><textarea rows={3} value={((vanilla.current.disabled as string[] | undefined) ?? []).join('\n')} onChange={(e) => vanilla.set('disabled', e.target.value.split('\n').map((s) => s.trim()).filter(Boolean))} /></Field>
            <Field label="Only these (one per line)"><textarea rows={3} value={((vanilla.current.only as string[] | undefined) ?? []).join('\n')} onChange={(e) => { const l = e.target.value.split('\n').map((s) => s.trim()).filter(Boolean); vanilla.set('only', l.length ? l : undefined); }} /></Field>
          </>
        )}
      </Section>
      <BlockDatalist />
    </div>
  );
}

export function General() {
  return (
    <>
      <aside className="panel"><GeneralPanel /></aside>
      <MapView />
    </>
  );
}
