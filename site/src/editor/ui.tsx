import { useState, type ReactNode } from 'react';
import { COMMON_BLOCKS } from '../data/biomes';
import type { Json, JsonObject } from '../lib/project';
import { useStore } from './store';

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="field" title={hint}>
      <span>{label}</span>
      {children}
    </label>
  );
}

export function NumInput({ value, onChange, min, max, step, placeholder }: { value: number | undefined; onChange: (v: number | undefined) => void; min?: number; max?: number; step?: number; placeholder?: string }) {
  return (
    <input
      type="number"
      value={value ?? ''}
      min={min}
      max={max}
      step={step}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value === '' ? undefined : Number(e.target.value))}
    />
  );
}

export function TextInput({ value, onChange, list, placeholder }: { value: string | undefined; onChange: (v: string | undefined) => void; list?: string; placeholder?: string }) {
  return <input type="text" value={value ?? ''} list={list} placeholder={placeholder} onChange={(e) => onChange(e.target.value === '' ? undefined : e.target.value)} />;
}

export function Lines({ value, onChange }: { value: string[] | undefined; onChange: (v: string[] | undefined) => void }) {
  return (
    <textarea
      rows={3}
      value={(value ?? []).join('\n')}
      onChange={(e) => {
        const list = e.target.value.split('\n').map((s) => s.trim()).filter(Boolean);
        onChange(list.length > 0 ? list : undefined);
      }}
    />
  );
}

export function Section({ title, children, open = false }: { title: string; children: ReactNode; open?: boolean }) {
  return (
    <details className="group" open={open}>
      <summary>{title}</summary>
      <div className="groupbody">{children}</div>
    </details>
  );
}

export function BlockDatalist() {
  return <datalist id="blocks">{COMMON_BLOCKS.map((b) => <option key={b} value={b} />)}</datalist>;
}

// ---- Block lists ---------------------------------------------------------------------------

interface BlockRow {
  block: string;
  weight: number;
}
interface BlockModel {
  rows: BlockRow[];
  method: 'random' | 'perlin';
  scale?: number;
}

function toModel(v: Json | undefined): BlockModel {
  if (v === undefined || v === null) return { rows: [], method: 'random' };
  const rowOf = (x: Json): BlockRow => (typeof x === 'string' ? { block: x, weight: 1 } : { block: String((x as JsonObject).block ?? ''), weight: Number((x as JsonObject).weight ?? 1) });
  if (typeof v === 'string') return { rows: [{ block: v, weight: 1 }], method: 'random' };
  if (Array.isArray(v)) return { rows: v.map(rowOf), method: 'random' };
  const o = v as JsonObject;
  return { rows: ((o.blocks as Json[] | undefined) ?? []).map(rowOf), method: (o.method as 'random' | 'perlin') ?? 'random', scale: o.scale as number | undefined };
}

function fromModel(m: BlockModel): Json | undefined {
  if (m.rows.length === 0) return undefined;
  const rows: Json[] = m.rows.map((r) => (r.weight === 1 ? r.block : { block: r.block, weight: r.weight }));
  if (m.method === 'perlin' || m.scale !== undefined) {
    const o: JsonObject = { blocks: rows, method: m.method };
    if (m.scale !== undefined) o.scale = m.scale;
    return o;
  }
  if (rows.length === 1 && typeof rows[0] === 'string') return rows[0];
  return rows;
}

/** A block id or a weighted mix of blocks (random or perlin). */
export function BlockListEditor({ value, onChange }: { value: Json | undefined; onChange: (v: Json | undefined) => void }) {
  const m = toModel(value);
  const set = (next: BlockModel) => onChange(fromModel(next));
  return (
    <div className="blocklist">
      {m.rows.map((r, i) => (
        <div className="row" key={i}>
          <input type="text" list="blocks" value={r.block} onChange={(e) => set({ ...m, rows: m.rows.map((x, j) => (j === i ? { ...x, block: e.target.value } : x)) })} />
          {m.rows.length > 1 && (
            <input className="narrow" type="number" min={1} value={r.weight} title="weight" onChange={(e) => set({ ...m, rows: m.rows.map((x, j) => (j === i ? { ...x, weight: Math.max(1, Number(e.target.value)) } : x)) })} />
          )}
          <button type="button" onClick={() => set({ ...m, rows: m.rows.filter((_, j) => j !== i) })}>×</button>
        </div>
      ))}
      <div className="row">
        <button type="button" onClick={() => set({ ...m, rows: [...m.rows, { block: 'minecraft:stone', weight: 1 }] })}>+ block</button>
        {m.rows.length > 1 && (
          <>
            <select value={m.method} onChange={(e) => set({ ...m, method: e.target.value as BlockModel['method'] })}>
              <option value="random">random</option>
              <option value="perlin">perlin</option>
            </select>
            {m.method === 'perlin' && <input className="narrow" type="number" min={1} placeholder="16" title="scale" value={m.scale ?? ''} onChange={(e) => set({ ...m, scale: e.target.value === '' ? undefined : Number(e.target.value) })} />}
          </>
        )}
      </div>
    </div>
  );
}

// ---- snow, features, carvers -------------------------------------------------------------------

type Mode = 'default' | 'off' | 'custom';

function ModeSelect({ mode, onChange }: { mode: Mode; onChange: (m: Mode) => void }) {
  return (
    <select value={mode} onChange={(e) => onChange(e.target.value as Mode)}>
      <option value="default">default (on)</option>
      <option value="off">off</option>
      <option value="custom">custom</option>
    </select>
  );
}

export function SnowEditor({ value, onChange }: { value: Json | undefined; onChange: (v: Json | undefined) => void }) {
  const mode: Mode = value === false ? 'off' : typeof value === 'object' && value !== null ? 'custom' : 'default';
  const o = (typeof value === 'object' && value !== null ? value : {}) as JsonObject;
  const setRange = (k: string, v: number | undefined) => {
    const next = { ...o };
    if (v === undefined) delete next[k];
    else next[k] = v;
    onChange(next);
  };
  return (
    <div className="row">
      <ModeSelect mode={mode} onChange={(m) => onChange(m === 'default' ? undefined : m === 'off' ? false : { min_y: 0 })} />
      {mode === 'custom' && (
        <>
          <NumInput value={o.min_y as number | undefined} onChange={(v) => setRange('min_y', v)} placeholder="min Y" />
          <NumInput value={o.max_y as number | undefined} onChange={(v) => setRange('max_y', v)} placeholder="max Y" />
        </>
      )}
    </div>
  );
}

export function FeaturesEditor({ value, onChange }: { value: Json | undefined; onChange: (v: Json | undefined) => void }) {
  const mode: Mode = value === false ? 'off' : typeof value === 'object' && value !== null ? 'custom' : 'default';
  const o = (typeof value === 'object' && value !== null ? value : {}) as JsonObject;
  const set = (k: string, v: Json | undefined) => {
    const next = { ...o };
    if (v === undefined) delete next[k];
    else next[k] = v;
    onChange(next);
  };
  return (
    <div>
      <ModeSelect mode={mode} onChange={(m) => onChange(m === 'default' ? undefined : m === 'off' ? false : { disabled: [] })} />
      {mode === 'custom' && (
        <>
          <Field label="Disabled features (one per line)"><Lines value={o.disabled as string[] | undefined} onChange={(v) => set('disabled', v)} /></Field>
          <Field label="Disabled steps (one per line)"><Lines value={o.disabled_steps as string[] | undefined} onChange={(v) => set('disabled_steps', v)} /></Field>
          <Field label="Only these features (one per line)"><Lines value={o.only as string[] | undefined} onChange={(v) => set('only', v)} /></Field>
        </>
      )}
    </div>
  );
}

export function CarversEditor({ value, onChange }: { value: Json | undefined; onChange: (v: Json | undefined) => void }) {
  const mode: Mode = value === false ? 'off' : typeof value === 'object' && value !== null ? 'custom' : 'default';
  const o = (typeof value === 'object' && value !== null ? value : {}) as JsonObject;
  return (
    <div className="row">
      <ModeSelect mode={mode} onChange={(m) => onChange(m === 'default' ? undefined : m === 'off' ? false : { cave: true, canyon: true, noise: true })} />
      {mode === 'custom' && (
        <>
          <label><input type="checkbox" checked={o.cave !== false} onChange={(e) => onChange({ ...o, cave: e.target.checked })} /> caves</label>
          <label><input type="checkbox" checked={o.canyon !== false} onChange={(e) => onChange({ ...o, canyon: e.target.checked })} /> canyons</label>
          <label><input type="checkbox" checked={(o.noise ?? o.cave) !== false} onChange={(e) => onChange({ ...o, noise: e.target.checked })} /> noise caves</label>
        </>
      )}
    </div>
  );
}

export function Collapsible({ title, children }: { title: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="collapsible">
      <button type="button" className="link" onClick={() => setOpen(!open)}>{open ? '▾' : '▸'} {title}</button>
      {open && children}
    </div>
  );
}

/** Quick show/hide of the reference images, for the sections that paint over them. */
export function RefToggle() {
  const refs = useStore((s) => s.project.references);
  const update = useStore((s) => s.updateReference);
  const setAll = useStore((s) => s.setAllReferencesVisible);
  if (refs.length === 0) return null;
  const anyVisible = refs.some((r) => r.visible);
  return (
    <div className="reftoggle">
      <button type="button" className={anyVisible ? 'on' : ''} onClick={() => setAll(!anyVisible)} title="Show or hide all the reference images">
        Reference images: {anyVisible ? 'shown' : 'hidden'}
      </button>
      {refs.length > 1 && refs.map((r) => (
        <label key={r.id}><input type="checkbox" checked={r.visible} onChange={(e) => update(r.id, { visible: e.target.checked })} /> {r.name}</label>
      ))}
    </div>
  );
}
