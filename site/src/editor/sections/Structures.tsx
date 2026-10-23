import { useRef, useState } from 'react';
import { VANILLA_STRUCTURES } from '../../data/structures';
import { resolvedTemplate } from '../../lib/zipIO';
import type { Mirror, Rotation, StructureTemplate } from '../../lib/project';
import { MapView, type Pt } from '../MapView';
import { useStore } from '../store';
import { Field, NumInput, RefToggle, TextInput } from '../ui';

const ROTATIONS: Rotation[] = ['none', 'clockwise_90', 'clockwise_180', 'counterclockwise_90'];
const MIRRORS: Mirror[] = ['none', 'left_right', 'front_back'];

/** Pixel footprint (width, depth) of a template once rotated, in map pixels. */
function footprint(t: StructureTemplate, scale: number): [number, number] {
  if (!t.size) return [3, 3];
  const swap = t.rotation === 'clockwise_90' || t.rotation === 'counterclockwise_90';
  const [sx, , sz] = t.size;
  return [Math.max(1, (swap ? sz : sx) / scale), Math.max(1, (swap ? sx : sz) / scale)];
}

function TemplateFields({ t, set }: { t: Pick<StructureTemplate, 'rotation' | 'mirror' | 'y' | 'yOffset' | 'entities'>; set: (patch: Partial<StructureTemplate>) => void }) {
  return (
    <>
      <Field label="Rotation">
        <select value={t.rotation} onChange={(e) => set({ rotation: e.target.value as Rotation })}>{ROTATIONS.map((r) => <option key={r}>{r}</option>)}</select>
      </Field>
      <Field label="Mirror">
        <select value={t.mirror} onChange={(e) => set({ mirror: e.target.value as Mirror })}>{MIRRORS.map((r) => <option key={r}>{r}</option>)}</select>
      </Field>
      <Field label="Y (empty: on the surface)"><NumInput value={t.y === 'surface' ? undefined : t.y} onChange={(v) => set({ y: v ?? 'surface' })} /></Field>
      {t.y === 'surface' && <Field label="Y offset"><NumInput value={t.yOffset} onChange={(v) => set({ yOffset: v ?? 0 })} /></Field>}
      <label><input type="checkbox" checked={t.entities} onChange={(e) => set({ entities: e.target.checked })} /> Place entities</label>
    </>
  );
}

export function Structures() {
  const project = useStore((s) => s.project);
  const addNbt = useStore((s) => s.addNbt);
  const addTemplate = useStore((s) => s.addTemplate);
  const updateTemplate = useStore((s) => s.updateTemplate);
  const removeTemplate = useStore((s) => s.removeTemplate);
  const addInstance = useStore((s) => s.addInstance);
  const updateInstance = useStore((s) => s.updateInstance);
  const removeInstance = useStore((s) => s.removeInstance);
  const focusOn = useStore((s) => s.focusOn);
  const hm = project.heightmap;
  const [templateId, setTemplateId] = useState<number | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [mode, setMode] = useState<'place' | 'move'>('place');
  const drag = useRef<number | null>(null);
  const [newId, setNewId] = useState('');
  const scale = (project.config.horizontal_scale as number | undefined) ?? 1;
  const template = project.templates.find((t) => t.id === templateId) ?? null;
  const instance = project.instances.find((i) => i.id === selected) ?? null;
  const instanceTemplate = instance ? resolvedTemplate(project, instance) : null;

  const hit = (p: Pt) =>
    [...project.instances].reverse().find((i) => {
      const t = resolvedTemplate(project, i);
      if (!t) return false;
      const [w, d] = footprint(t, scale);
      return p.x >= i.x - 1 && p.x <= i.x + w && p.z >= i.z - 1 && p.z <= i.z + d;
    });

  const clampTo = (p: Pt) => (hm ? { x: Math.max(0, Math.min(hm.width - 1, p.x)), z: Math.max(0, Math.min(hm.height - 1, p.z)) } : p);

  const down = (p: Pt) => {
    if (mode === 'move' && instance) {
      // Move mode: the selected placement jumps to the click and follows the drag
      updateInstance(instance.id, clampTo(p));
      drag.current = instance.id;
      return;
    }
    const h = hit(p);
    if (h) {
      setSelected(h.id);
      drag.current = h.id;
      return;
    }
    if (!hm || !template || p.x < 0 || p.z < 0 || p.x >= hm.width || p.z >= hm.height) {
      setSelected(null);
      return;
    }
    const id = addInstance(template.id, p.x, p.z);
    setSelected(id);
  };
  const nudge = (dx: number, dz: number) => {
    if (instance) updateInstance(instance.id, clampTo({ x: instance.x + dx, z: instance.z + dz }));
  };
  const move = (p: Pt) => {
    if (drag.current !== null && hm) updateInstance(drag.current, clampTo(p));
  };
  const up = () => { drag.current = null; };

  const markers = project.instances.map((i) => {
    const t = resolvedTemplate(project, i);
    if (!t) return null;
    const [w, d] = footprint(t, scale);
    const sel = i.id === selected;
    return (
      <g key={i.id}>
        {t.kind === 'template' && <rect x={i.x} y={i.z} width={w} height={d} fill={sel ? 'rgba(255,220,0,.35)' : 'rgba(255,120,0,.3)'} stroke={sel ? '#ffd800' : '#ff7800'} strokeWidth={0.4} />}
        <circle cx={i.x + 0.5} cy={i.z + 0.5} r={Math.max(1.5, scale === 1 ? 2 : 1.5)} fill={t.kind === 'template' ? '#ffd800' : '#d050ff'} stroke="#000" strokeWidth={0.4} />
      </g>
    );
  });

  return (
    <>
      <aside className="panel">
        <h2>Structures</h2>
        <RefToggle />
        <h3>Templates</h3>
        <p className="hint">A template is a reusable preset: a .nbt file or a registered structure with its settings. Select one, then click on the map to place it. Drag a marker to move it.</p>
        <input type="file" accept=".nbt" id="nbt" hidden onChange={async (e) => {
          const f = e.target.files?.[0];
          if (f) setTemplateId(addNbt(f.name, new Uint8Array(await f.arrayBuffer())).id);
          e.target.value = '';
        }} />
        <div className="row">
          <button type="button" onClick={() => document.getElementById('nbt')?.click()}>+ .nbt file…</button>
        </div>
        <div className="row">
          <input type="text" list="structure-ids" placeholder="minecraft:village_plains" value={newId} onChange={(e) => setNewId(e.target.value)} />
          <button type="button" disabled={!newId.trim()} onClick={() => {
            const id = newId.trim();
            setTemplateId(addTemplate({ name: id.replace(/^minecraft:/, ''), kind: 'structure', structureId: id, rotation: 'none', mirror: 'none', y: 'surface', yOffset: 0, entities: false }).id);
            setNewId('');
          }}>+ structure</button>
        </div>
        <ul className="list">
          {project.templates.map((t) => (
            <li key={t.id} className={t.id === templateId ? 'active' : ''} onClick={() => setTemplateId(t.id)}>
              <span className="swatch" style={{ background: t.kind === 'template' ? '#ffd800' : '#d050ff' }} />
              <span>{t.name}</span>
              <small>{project.instances.filter((i) => i.templateId === t.id).length}×</small>
            </li>
          ))}
        </ul>
        {template && (
          <div className="card">
            <Field label="Name"><TextInput value={template.name} onChange={(v) => updateTemplate(template.id, { name: v ?? '' })} /></Field>
            {template.kind === 'structure' ? (
              <>
                <Field label="Structure id"><TextInput value={template.structureId} list="structure-ids" onChange={(v) => updateTemplate(template.id, { structureId: v })} /></Field>
                <Field label="Fallback id (if the mod is missing)"><TextInput value={template.fallbackId} list="structure-ids" onChange={(v) => updateTemplate(template.id, { fallbackId: v })} /></Field>
                <Field label="Retries if it can't be generated (moves up to 2 chunks)"><NumInput value={template.retries} min={0} max={64} placeholder="32" onChange={(v) => updateTemplate(template.id, { retries: v })} /></Field>
              </>
            ) : (
              <>
                <p className="hint">{template.path}{template.size ? ` · ${template.size.join('×')} blocks` : ''}</p>
                <TemplateFields t={template} set={(patch) => updateTemplate(template.id, patch)} />
              </>
            )}
            <button type="button" className="danger" onClick={() => { removeTemplate(template.id); setTemplateId(null); }}>Delete template and its placements</button>
          </div>
        )}
        <h3>Placed ({project.instances.length})</h3>
        <ul className="list">
          {project.instances.map((i) => {
            const t = project.templates.find((x) => x.id === i.templateId);
            return (
              <li key={i.id} className={i.id === selected ? 'active' : ''} onClick={() => { setSelected(i.id); focusOn(i.x, i.z); }}>
                <span className="swatch" style={{ background: t?.kind === 'template' ? '#ffd800' : '#d050ff' }} />
                <span>{t?.name ?? '?'}</span>
                <small>{i.x}, {i.z}</small>
              </li>
            );
          })}
        </ul>
        {instance && instanceTemplate && (
          <div className="card">
            <p><strong>{instanceTemplate.name}</strong></p>
            <div className="row">
              <button type="button" className={mode === 'move' ? 'on' : ''} onClick={() => setMode(mode === 'move' ? 'place' : 'move')} title="While on, a click or drag on the map moves this placement">
                {mode === 'move' ? 'Moving: click the map' : 'Move on map'}
              </button>
              <button type="button" onClick={() => focusOn(instance.x, instance.z)}>Find</button>
            </div>
            <div className="nudge" title="Move by 1 pixel (Shift: 10)">
              <span />
              <button type="button" onClick={(e) => nudge(0, e.shiftKey ? -10 : -1)}>↑</button>
              <span />
              <button type="button" onClick={(e) => nudge(e.shiftKey ? -10 : -1, 0)}>←</button>
              <button type="button" onClick={(e) => nudge(0, e.shiftKey ? 10 : 1)}>↓</button>
              <button type="button" onClick={(e) => nudge(e.shiftKey ? 10 : 1, 0)}>→</button>
            </div>
            <Field label="Pixel X, Z">
              <div className="row">
                <NumInput value={instance.x} onChange={(v) => updateInstance(instance.id, { x: v ?? 0 })} />
                <NumInput value={instance.z} onChange={(v) => updateInstance(instance.id, { z: v ?? 0 })} />
              </div>
            </Field>
            {instanceTemplate.kind === 'template' && (
              <>
                <p className="hint">Settings below replace the template's for this placement only.</p>
                <TemplateFields t={instanceTemplate} set={(patch) => updateInstance(instance.id, { overrides: { ...instance.overrides, ...patch } })} />
              </>
            )}
            <button type="button" className="danger" onClick={() => { removeInstance(instance.id); setSelected(null); }}>Remove</button>
          </div>
        )}
        <datalist id="structure-ids">{VANILLA_STRUCTURES.map((s) => <option key={s} value={s} />)}</datalist>
      </aside>
      <MapView onDown={down} onMove={move} onUp={up} markers={markers} cursor={mode === 'move' && instance ? 'move' : template ? 'copy' : 'default'} />
    </>
  );
}
