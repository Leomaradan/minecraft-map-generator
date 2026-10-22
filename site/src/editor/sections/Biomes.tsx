import { useRef, useState } from 'react';
import { VANILLA_BIOMES } from '../../data/biomes';
import { parseColor } from '../../lib/zipIO';
import type { BiomeEntry, Json, JsonObject } from '../../lib/project';
import { floodFill, stroke } from '../brush';
import { MapView, type Pt } from '../MapView';
import { useStore } from '../store';
import { BlockDatalist, BlockListEditor, CarversEditor, Collapsible, FeaturesEditor, Field, NumInput, RefToggle, SnowEditor, TextInput } from '../ui';

type Tool = 'brush' | 'eraser' | 'fill' | 'pick';

const SUBSTITUTES = [
  { key: 'biome_river', label: 'River', setting: 'river_width', settingLabel: 'River width', ph: '4' },
  { key: 'biome_mountain', label: 'Mountain', setting: 'mountain_y', settingLabel: 'From Y', ph: 'sea level + 64' },
  { key: 'biome_border', label: 'Border', setting: 'border_width', settingLabel: 'Border width', ph: '4' },
  { key: 'biome_underground', label: 'Underground', setting: 'underground_depth', settingLabel: 'Depth below ground', ph: '16' },
] as const;

function objectOf(v: Json | undefined): JsonObject {
  return typeof v === 'object' && v !== null && !Array.isArray(v) ? v : typeof v === 'string' ? { biome: v } : {};
}

/** Compact form of a substitute: a plain id when it has nothing else. */
function compact(o: JsonObject): Json {
  return Object.keys(o).length === 1 && typeof o.biome === 'string' ? o.biome : o;
}

function SurfaceFields({ data, set }: { data: JsonObject; set: (k: string, v: Json | undefined) => void }) {
  return (
    <>
      <Field label="Top block"><BlockListEditor value={data.top} onChange={(v) => set('top', v)} /></Field>
      <Field label="Filler block"><BlockListEditor value={data.filler} onChange={(v) => set('filler', v)} /></Field>
      <Field label="Filler depth"><NumInput value={data.filler_depth as number | undefined} min={0} onChange={(v) => set('filler_depth', v)} /></Field>
      <Field label="Under water or lava"><BlockListEditor value={data.underwater} onChange={(v) => set('underwater', v)} /></Field>
      <Field label="Snow"><SnowEditor value={data.snow} onChange={(v) => set('snow', v)} /></Field>
      <Field label="Features"><FeaturesEditor value={data.features} onChange={(v) => set('features', v)} /></Field>
      <Field label="Carvers"><CarversEditor value={data.carvers} onChange={(v) => set('carvers', v)} /></Field>
    </>
  );
}

function EntryEditor({ entry }: { entry: BiomeEntry }) {
  const update = useStore((s) => s.updateBiome);
  const data = entry.data;
  const set = (k: string, v: Json | undefined) =>
    update(entry.id, (d) => {
      const next = { ...d };
      if (v === undefined) delete next[k];
      else next[k] = v;
      return next;
    });
  return (
    <div className="card">
      <Field label="Biome"><TextInput value={data.biome as string | undefined} list="biome-ids" onChange={(v) => set('biome', v ?? '')} /></Field>
      <SurfaceFields data={data} set={set} />
      <Collapsible title="Substitute biomes">
        {SUBSTITUTES.map((s) => {
          const sub = data[s.key];
          const enabled = sub !== undefined;
          const o = objectOf(sub);
          const setSub = (k: string, v: Json | undefined) => {
            const next = { ...o };
            if (v === undefined) delete next[k];
            else next[k] = v;
            set(s.key, compact(next));
          };
          return (
            <div key={s.key} className="substitute">
              <label>
                <input type="checkbox" checked={enabled} onChange={(e) => set(s.key, e.target.checked ? 'minecraft:plains' : undefined)} /> {s.label}
              </label>
              {enabled && (
                <>
                  <Field label="Biome"><TextInput value={o.biome as string | undefined} list="biome-ids" onChange={(v) => setSub('biome', v ?? '')} /></Field>
                  <Field label={s.settingLabel}><NumInput value={data[s.setting] as number | undefined} placeholder={s.ph} onChange={(v) => set(s.setting, v)} /></Field>
                  {s.key !== 'biome_underground' && (
                    <Collapsible title="Own surface and decoration"><SurfaceFields data={o} set={setSub} /></Collapsible>
                  )}
                </>
              )}
            </div>
          );
        })}
      </Collapsible>
    </div>
  );
}

export function Biomes() {
  const project = useStore((s) => s.project);
  const addBiome = useStore((s) => s.addBiome);
  const removeBiome = useStore((s) => s.removeBiome);
  const update = useStore((s) => s.updateBiome);
  const updateProject = useStore((s) => s.updateProject);
  const paint = useStore((s) => s.paint);
  const commit = useStore((s) => s.commitStroke);
  const hm = project.heightmap;
  const [tool, setTool] = useState<Tool>('brush');
  const [size, setSize] = useState(8);
  const [active, setActive] = useState<number | null>(null);
  const last = useRef<Pt | null>(null);
  const entry = project.biomes.find((b) => b.id === active) ?? null;
  const defaultEntry = project.biomes.find((b) => b.id === project.defaultBiomeId);

  const drawOverlay = (px: Uint8ClampedArray, w: number, h: number) => {
    const colors = new Map(project.biomes.map((b) => [b.id, parseColor(b.color) ?? [128, 128, 128]]));
    const hidden = new Set(project.biomes.filter((e) => e.hidden).map((e) => e.id));
    const fallback = defaultEntry && !defaultEntry.hidden ? colors.get(defaultEntry.id) : undefined;
    for (let i = 0; i < w * h; i++) {
      const id = project.biomeIndex[i];
      if (hidden.has(id)) continue;
      const c = colors.get(id) ?? fallback;
      if (!c) continue;
      px[i * 4] = c[0];
      px[i * 4 + 1] = c[1];
      px[i * 4 + 2] = c[2];
      px[i * 4 + 3] = project.biomeIndex[i] ? 255 : 110;
    }
  };

  const down = (p: Pt) => {
    if (!hm || p.x < 0 || p.z < 0 || p.x >= hm.width || p.z >= hm.height) return;
    const idx = p.z * hm.width + p.x;
    if (tool === 'pick') {
      const id = project.biomeIndex[idx] || project.defaultBiomeId;
      if (id) setActive(id);
      return;
    }
    if (tool !== 'eraser' && !entry) return;
    if (tool === 'fill') {
      const from = project.biomeIndex[idx];
      paint('biomeIndex', floodFill(idx, hm.width, hm.height, (q) => project.biomeIndex[q] === from), entry!.id);
      commit();
      return;
    }
    last.current = null;
    move(p);
  };

  const move = (p: Pt) => {
    if (!hm || (tool !== 'brush' && tool !== 'eraser')) return;
    if (tool === 'brush' && !entry) return;
    paint('biomeIndex', stroke(last.current, p, size, hm.width, hm.height), tool === 'eraser' ? 0 : entry!.id);
    last.current = p;
  };

  const up = () => {
    last.current = null;
    commit();
  };

  const colorTaken = (c: string, id: number) => project.biomes.some((b) => b.id !== id && b.color.toUpperCase() === c.toUpperCase());

  return (
    <>
      <aside className="panel">
        <h2>Biomes</h2>
        <div className="tools">
          {(['brush', 'eraser', 'fill', 'pick'] as Tool[]).map((t) => (
            <button key={t} type="button" className={tool === t ? 'on' : ''} onClick={() => setTool(t)}>{t}</button>
          ))}
        </div>
        {(tool === 'brush' || tool === 'eraser') && (
          <Field label={`Brush size ${size}`}><input type="range" min={1} max={80} value={size} onChange={(e) => setSize(Number(e.target.value))} /></Field>
        )}
        <RefToggle />
        <p className="hint">Each biome gets its own color in biomes.png. Unpainted pixels (shown faded) use the default biome. The eye hides a biome on the map while you edit; it doesn't change the export.</p>
        <button type="button" onClick={() => setActive(addBiome().id)}>+ Biome</button>
        <ul className="list">
          {project.biomes.map((b) => (
            <li key={b.id} className={b.id === active ? 'active' : ''} onClick={() => setActive(b.id)}>
              <span className="swatch" style={{ background: b.color }} />
              <span>{String(b.data.biome ?? '').replace('minecraft:', '')}</span>
              {b.id === project.defaultBiomeId && <small>default</small>}
              <button
                type="button"
                className="icon"
                title={b.hidden ? 'Show this biome on the map' : 'Hide this biome on the map'}
                onClick={(e) => { e.stopPropagation(); updateProject((p) => { p.biomes = p.biomes.map((x) => (x.id === b.id ? { ...x, hidden: !x.hidden } : x)); }); }}
              >{b.hidden ? '🙈' : '👁'}</button>
            </li>
          ))}
        </ul>
        {entry && (
          <>
            <div className="row">
              <input type="color" value={entry.color.toLowerCase()} onChange={(e) => !colorTaken(e.target.value, entry.id) && update(entry.id, (d) => d, e.target.value.toUpperCase())} />
              <button type="button" onClick={() => updateProject((p) => { p.defaultBiomeId = entry.id; })} disabled={entry.id === project.defaultBiomeId}>Make default</button>
              <button type="button" className="danger" onClick={() => { removeBiome(entry.id); setActive(null); }}>Delete</button>
            </div>
            <EntryEditor entry={entry} />
          </>
        )}
        <datalist id="biome-ids">{VANILLA_BIOMES.map((b) => <option key={b} value={b} />)}</datalist>
        <BlockDatalist />
      </aside>
      <MapView drawOverlay={drawOverlay} overlayKey={`${project.biomes.map((b) => b.color + (b.hidden ? 'h' : '')).join()}|${project.defaultBiomeId}`} overlayOpacity={0.6} onDown={down} onMove={move} onUp={up} cursor={tool === 'pick' ? 'copy' : 'crosshair'} />
    </>
  );
}
