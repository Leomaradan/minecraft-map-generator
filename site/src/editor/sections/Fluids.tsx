import { useEffect, useMemo, useRef, useState } from 'react';
import { ARROW_CELL, spillLevel, type FluidResult } from '../../lib/fluids';
import { fromY, resolveHeightMapping, toY } from '../../lib/heightMapping';
import type { FluidBody } from '../../lib/project';
import { addNode, linkAt, nodeAt, rasterizeRiver, removeNode, resolveRiver, splitLink, toggleLink, updateNode, type ResolvedRiver, type RiverRaster } from '../../lib/rivers';
import { polygon, stroke } from '../brush';
import { MapView, type Pt } from '../MapView';
import { useStore } from '../store';
import { solveAsync } from '../workerClient';
import { Field, NumInput } from '../ui';
import { arrowPath, RiverCard, riverMarkers } from './RiverNodes';

type Tool = 'brush' | 'eraser';

const COLORS: Record<FluidBody['fluid'], Record<FluidBody['mode'], [number, number, number]>> = {
  water: { lake: [40, 110, 235], river: [40, 200, 210], flow: [130, 100, 235], raw: [110, 140, 185] },
  lava: { lake: [225, 60, 20], river: [250, 165, 30], flow: [235, 90, 150], raw: [175, 95, 70] },
};
const LOW: [number, number, number] = [255, 0, 60];

export function bodyColor(b: FluidBody): [number, number, number] {
  return COLORS[b.fluid][b.mode];
}

const hex = (c: number[]) => `rgb(${c.map((v) => Math.round(v)).join(',')})`;

/** Darker with depth, so the slope of the bottom shows through the surface. */
function shade(c: [number, number, number], depth: number): [number, number, number] {
  const f = 1 - Math.min(Math.max(depth, 0) / 12, 1) * 0.45;
  return [c[0] * f, c[1] * f, c[2] * f];
}

type NewMode = 'lake' | 'river' | 'flow';

const KINDS: { fluid: FluidBody['fluid']; mode: NewMode; label: string }[] = [
  { fluid: 'water', mode: 'lake', label: '+ Lake' },
  { fluid: 'water', mode: 'river', label: '+ River' },
  { fluid: 'water', mode: 'flow', label: '+ Free water flow' },
  { fluid: 'lava', mode: 'lake', label: '+ Lava lake' },
  { fluid: 'lava', mode: 'river', label: '+ Lava river' },
  { fluid: 'lava', mode: 'flow', label: '+ Free lava flow' },
];

type Graph = Pick<FluidBody, 'nodes' | 'edges'>;

export function Fluids() {
  const project = useStore((s) => s.project);
  const rev = useStore((s) => s.rev);
  const paint = useStore((s) => s.paint);
  const commit = useStore((s) => s.commitStroke);
  const addBody = useStore((s) => s.addBody);
  const updateBody = useStore((s) => s.updateBody);
  const removeBody = useStore((s) => s.removeBody);
  const hm = project.heightmap;
  const labels = project.fluidLabels;
  const [tool, setTool] = useState<Tool>('brush');
  const [size, setSize] = useState(5);
  const [active, setActive] = useState<number | null>(null);
  const [result, setResult] = useState<FluidResult | null>(null);
  const [lasso, setLasso] = useState<{ points: Pt[]; cut: boolean } | null>(null);
  const [node, setNode] = useState<number | null>(null);
  const last = useRef<Pt | null>(null);
  const drag = useRef<number | null>(null);
  /** Zoom of the map (screen pixels per map pixel), for hit tests in screen pixels. */
  const zoom = useRef(1);
  const mapping = useMemo(() => resolveHeightMapping(project.config.height as never, hm?.bitDepth ?? 8), [project.config.height, hm?.bitDepth]);
  const body = project.bodies.find((b) => b.id === active) ?? null;
  const numbers = useMemo(() => new Map(project.bodies.map((b, i) => [b.id, i + 1])), [project.bodies]);
  const lakes = useMemo(() => new Set(project.bodies.filter((b) => b.mode === 'lake').map((b) => b.id)), [project.bodies]);

  // Rivers made of nodes are computed here, so the map follows every edit at once
  const rivers = useMemo(() => {
    const out = new Map<number, { resolved: ResolvedRiver; raster: RiverRaster }>();
    if (!hm) return out;
    const groundY = (x: number, z: number) => toY(mapping, hm.data[z * hm.width + x] ?? 0);
    for (const b of project.bodies) {
      if (b.mode !== 'river') continue;
      const resolved = resolveRiver(b, groundY);
      out.set(b.id, { resolved, raster: rasterizeRiver(b, resolved, hm.width, hm.height) });
    }
    return out;
  }, [project.bodies, hm, mapping]);
  useEffect(() => setNode(null), [active]);

  // Computed levels (in a worker), requested shortly after the last change
  useEffect(() => {
    if (!hm || project.bodies.length === 0) {
      setResult(null);
      return;
    }
    let cancelled = false;
    const t = setTimeout(() => {
      solveAsync(labels, project.fluidRaw, project.bodies, hm, project.config.height, rev).then((r) => !cancelled && setResult(r), () => undefined);
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [hm, project.bodies, labels, project.fluidRaw, project.config.height, rev]);

  const setGraph = (g: Graph) => { if (active !== null) updateBody(active, { nodes: g.nodes, edges: g.edges }); };
  /** The selected body as it is in the store now (several edits can happen between two renders). */
  const current = () => useStore.getState().project.bodies.find((b) => b.id === active) ?? null;

  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement || e.target instanceof HTMLSelectElement) return;
      if (e.key === 'Escape') {
        if (node !== null) setNode(null);
        else setActive(null);
      } else if ((e.key === 'Delete' || e.key === 'Backspace') && node !== null) {
        const b = current();
        if (b?.mode !== 'river') return;
        setGraph(removeNode(b, node));
        setNode(null);
      }
    };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  });

  const counts = useMemo(() => {
    const c = new Map<number, number>();
    for (const id of labels) if (id) c.set(id, (c.get(id) ?? 0) + 1);
    return c;
  }, [labels, rev]); // eslint-disable-line react-hooks/exhaustive-deps
  const used = (id: number) => counts.get(id) ?? 0;

  // Ground under the zone of the active lake, in world Y
  const zone = useMemo(() => {
    if (!hm || body?.mode !== 'lake') return null;
    let lo = Infinity;
    let hi = -Infinity;
    for (let i = 0; i < labels.length; i++) {
      if (labels[i] !== body.id) continue;
      lo = Math.min(lo, hm.data[i]);
      hi = Math.max(hi, hm.data[i]);
    }
    return lo === Infinity ? null : { lo: toY(mapping, lo), hi: toY(mapping, hi) };
  }, [hm, body?.id, body?.mode, labels, rev, mapping]); // eslint-disable-line react-hooks/exhaustive-deps

  const lakeStats = useMemo(() => {
    if (!hm || body?.mode !== 'lake' || body.level === undefined) return null;
    const surface = fromY(mapping, body.level);
    let wet = 0;
    let deepest = 0;
    for (let i = 0; i < labels.length; i++) {
      if (labels[i] !== body.id || hm.data[i] >= surface) continue;
      wet++;
      deepest = Math.max(deepest, surface - hm.data[i]);
    }
    return { wet, depth: deepest * mapping.scale };
  }, [hm, body?.id, body?.mode, body?.level, labels, rev, mapping]); // eslint-disable-line react-hooks/exhaustive-deps

  const range = useMemo(() => {
    if (!result || !body || body.mode === 'lake' || body.mode === 'river') return null;
    let lo = Infinity;
    let hi = -Infinity;
    for (let i = 0; i < labels.length; i++) {
      if (labels[i] !== body.id) continue;
      lo = Math.min(lo, result.levels[i]);
      hi = Math.max(hi, result.levels[i]);
    }
    return lo === Infinity ? null : [toY(mapping, lo), toY(mapping, hi)];
  }, [result, body, labels, mapping]);

  const drawOverlay = (px: Uint8ClampedArray, w: number, h: number) => {
    if (!hm) return;
    const ground = hm.data;
    const info = new Map(project.bodies.map((b) => [b.id, {
      body: b,
      color: bodyColor(b),
      surface: b.mode === 'lake' && b.level !== undefined ? fromY(mapping, b.level) : -1,
      on: b.id === active,
    }]));
    const n = w * h;
    for (let i = 0; i < n; i++) {
      const id = labels[i];
      if (id === 0) continue;
      const b = info.get(id);
      if (!b) continue;
      let c: [number, number, number];
      let a: number;
      if (b.body.mode === 'lake') {
        const x = i % w;
        const edge = x === 0 || x === w - 1 || i < w || i + w >= n || labels[i - 1] !== id || labels[i + 1] !== id || labels[i - w] !== id || labels[i + w] !== id;
        if (ground[i] < b.surface) {
          c = shade(b.color, (b.surface - ground[i]) * mapping.scale);
          a = b.on ? 255 : 220;
        } else if (edge) {
          c = b.on ? [255, 255, 255] : b.color;
          a = b.on ? 230 : 150;
        } else {
          c = b.color;
          a = b.on ? 70 : 35;
        }
      } else {
        const low = result?.low[i] === 1;
        c = low ? LOW : shade(b.color, ((result?.levels[i] ?? 0) - ground[i]) * mapping.scale);
        a = b.on ? 255 : 200;
      }
      px[i * 4] = c[0];
      px[i * 4 + 1] = c[1];
      px[i * 4 + 2] = c[2];
      px[i * 4 + 3] = a;
    }
    // Rivers made of nodes, the selected one last so it is on top
    const order = [...rivers.keys()].sort((u, v) => (u === active ? 1 : 0) - (v === active ? 1 : 0));
    for (const id of order) {
      const b = project.bodies.find((x) => x.id === id)!;
      const { index, level } = rivers.get(id)!.raster;
      const color = bodyColor(b);
      const on = id === active;
      for (let k = 0; k < index.length; k++) {
        const i = index[k];
        const surface = Math.round((level[k] - mapping.baseY) / mapping.scale);
        let c = color;
        let a: number;
        if (ground[i] < surface) {
          c = shade(color, (surface - ground[i]) * mapping.scale);
          a = on ? 255 : 220;
        } else {
          // Dry part of the band: faint, under any other body
          if (px[i * 4 + 3] > 0) continue;
          a = on ? 70 : 35;
        }
        px[i * 4] = c[0];
        px[i * 4 + 1] = c[1];
        px[i * 4 + 2] = c[2];
        px[i * 4 + 3] = a;
      }
    }
  };
  const overlayKey = useMemo(() => ({}), [active, result, project.bodies, mapping, rivers]); // eslint-disable-line react-hooks/exhaustive-deps

  const inside = (p: Pt) => !!hm && p.x >= 0 && p.z >= 0 && p.x < hm.width && p.z < hm.height;

  const down = (p: Pt, e: React.PointerEvent) => {
    if (!hm) return;
    if (!body) {
      // Nothing selected: a click selects the body under it
      if (!inside(p)) return;
      if (labels[p.z * hm.width + p.x]) {
        setActive(labels[p.z * hm.width + p.x]);
        return;
      }
      for (const b of project.bodies) {
        const r = rivers.get(b.id);
        if (!r) continue;
        const link = linkAt(b, r.resolved, p.x, p.z);
        if ((link && link.distance <= Math.max(link.radius, 4 / zoom.current)) || nodeAt(b, p.x, p.z, 6 / zoom.current)) {
          setActive(b.id);
          return;
        }
      }
      return;
    }
    if (body.mode === 'raw') return;
    if (body.mode === 'river') {
      riverDown(p, e.shiftKey);
      return;
    }
    if (body.mode === 'lake') {
      setLasso({ points: [p], cut: e.shiftKey });
      return;
    }
    last.current = null;
    move(p);
  };

  const riverDown = (p: Pt, shift: boolean) => {
    const b = current();
    if (!hm || !b) return;
    const tolerance = Math.max(1, 8 / zoom.current);
    const hit = nodeAt(b, p.x, p.z, tolerance);
    if (hit) {
      if (shift && node !== null && node !== hit.id) setGraph(toggleLink(b, node, hit.id));
      else {
        setNode(hit.id);
        drag.current = hit.id;
      }
      return;
    }
    if (!inside(p)) return;
    const r = rivers.get(b.id);
    const link = r && linkAt(b, r.resolved, p.x, p.z);
    const g = link && link.distance <= tolerance * 0.6 ? splitLink(b, link.edge, p.x, p.z) : addNode(b, p.x, p.z, node);
    setGraph(g);
    setNode(g.id);
    drag.current = g.id;
  };

  const move = (p: Pt) => {
    if (!hm || !body) return;
    if (body.mode === 'river') {
      const b = current();
      if (drag.current === null || !b) return;
      setGraph(updateNode(b, drag.current, { x: Math.max(0, Math.min(hm.width - 1, p.x)), z: Math.max(0, Math.min(hm.height - 1, p.z)) }));
      return;
    }
    if (body.mode === 'lake') {
      setLasso((l) => {
        if (!l) return l;
        const end = l.points[l.points.length - 1];
        return end.x === p.x && end.z === p.z ? l : { ...l, points: [...l.points, p] };
      });
      return;
    }
    if (body.mode !== 'flow') return;
    const pixels = stroke(last.current, p, size, hm.width, hm.height);
    if (tool === 'eraser') {
      paint('fluidLabels', pixels.filter((q) => labels[q] === body.id), 0);
    } else {
      // A free flow may run through the zone of a lake, but never over another flow or an imported layer
      const takes = (q: number) => labels[q] === 0 || lakes.has(labels[q]);
      paint('fluidLabels', pixels.filter(takes), body.id);
    }
    last.current = p;
  };

  const up = () => {
    last.current = null;
    drag.current = null;
    if (lasso && hm && body?.mode === 'lake') {
      const pixels = polygon(lasso.points, hm.width, hm.height);
      if (lasso.cut) paint('fluidLabels', pixels.filter((q) => labels[q] === body.id), 0);
      else paint('fluidLabels', pixels.filter((q) => labels[q] === 0), body.id);
      if (!lasso.cut && body.level === undefined) {
        const spill = spillLevel(labels, body.id, hm.data, hm.width);
        if (spill >= 0) updateBody(body.id, { level: toY(mapping, spill) });
      }
      setLasso(null);
    }
    commit();
  };

  const clearZone = () => {
    if (!body) return;
    const pixels: number[] = [];
    for (let i = 0; i < labels.length; i++) if (labels[i] === body.id) pixels.push(i);
    paint('fluidLabels', pixels, 0);
    commit();
  };

  const markers = (k: number) => {
    zoom.current = k;
    const out: React.ReactNode[] = [];
    for (const b of project.bodies) {
      const r = rivers.get(b.id);
      if (r) out.push(...riverMarkers(b, r.resolved, k, b.id === active, b.id === active ? node : null, numbers.get(b.id)));
    }
    if (result) {
      // Flow arrows, regrouped so they stay apart when zoomed out
      const cell = Math.max(ARROW_CELL, 28 / k);
      const groups = new Map<string, number[]>();
      const a = result.arrows;
      for (let i = 0; i < a.length; i += 4) {
        const key = `${Math.floor(a[i] / cell)},${Math.floor(a[i + 1] / cell)}`;
        const g = groups.get(key) ?? [0, 0, 0, 0, 0];
        g[0] += a[i]; g[1] += a[i + 1]; g[2] += a[i + 2]; g[3] += a[i + 3]; g[4]++;
        groups.set(key, g);
      }
      const len = Math.min(cell * 0.7, 16 / k);
      for (const [key, [sx, sz, dx, dz, count]] of groups) {
        const l = Math.hypot(dx, dz);
        if (l < 1e-6) continue;
        out.push(<path key={`a${key}`} d={arrowPath(sx / count, sz / count, dx / l, dz / l, len)} className="flow" style={{ strokeWidth: 1.6 / k }} />);
      }
      const an = result.anchors;
      for (let i = 0; i < an.length; i += 3) {
        const num = numbers.get(an[i]);
        if (num === undefined) continue;
        out.push(<text key={`n${i}`} x={an[i + 1]} y={an[i + 2]} className={an[i] === active ? 'bodynum on' : 'bodynum'} style={{ fontSize: 13 / k, strokeWidth: 3 / k }}>{num}</text>);
      }
    }
    if (lasso) {
      const pts = lasso.points.map((p) => `${p.x + 0.5},${p.z + 0.5}`).join(' ');
      out.push(<polygon key="lasso" points={pts} className={lasso.cut ? 'lasso cut' : 'lasso'} style={{ strokeWidth: 1.5 / k }} />);
    }
    return out;
  };

  const select = (id: number) => setActive(id === active ? null : id);
  const add = (fluid: FluidBody['fluid'], mode: NewMode) => {
    setActive(addBody(fluid, mode).id);
    setTool('brush');
  };

  return (
    <>
      <aside className="panel">
        <h2>Water and lava</h2>
        <div className="addgrid">
          {KINDS.map((k) => (
            <button key={k.label} type="button" onClick={() => add(k.fluid, k.mode)}>
              <span className="swatch" style={{ background: hex(COLORS[k.fluid][k.mode]) }} /> {k.label}
            </button>
          ))}
        </div>
        <ul className="list">
          {project.bodies.map((b) => (
            <li key={b.id} className={b.id === active ? 'active' : ''} onClick={() => select(b.id)}>
              <span className="swatch num" style={{ background: hex(bodyColor(b)) }}>{numbers.get(b.id)}</span>
              <span>{b.name}</span>
              <small>{b.mode === 'raw' ? 'read-only · ' : ''}{b.mode === 'river' ? `${b.nodes?.length ?? 0} nodes` : `${used(b.id)} px`}</small>
            </li>
          ))}
        </ul>
        {!body && project.bodies.length > 0 && <p className="hint">Click a body on the map or in the list to select it. Esc deselects.</p>}
        {body && (
          <div className="card">
            <Field label="Name"><input type="text" value={body.name} onChange={(e) => updateBody(body.id, { name: e.target.value })} /></Field>
            {body.mode === 'lake' && (
              <>
                <p className="hint">
                  Draw the outline of the zone on the map: it closes when you release the button. Draw again to add to it, hold Shift to cut
                  from it. The {body.fluid} fills the zone wherever the ground is below the surface.
                </p>
                {zone ? (
                  <>
                    <Field label={`Surface Y ${body.level === undefined ? '-' : +body.level.toFixed(2)}`}>
                      <input
                        type="range"
                        min={Math.floor(zone.lo)}
                        max={Math.ceil(zone.hi) + 1}
                        step={1}
                        value={body.level ?? Math.floor(zone.lo)}
                        onChange={(e) => updateBody(body.id, { level: Number(e.target.value) })}
                      />
                    </Field>
                    <Field label="Exact surface Y"><NumInput value={body.level} onChange={(v) => updateBody(body.id, { level: v })} /></Field>
                    <p className="ok">
                      {lakeStats && lakeStats.wet > 0
                        ? `${lakeStats.wet} of ${used(body.id)} px filled, up to ${+lakeStats.depth.toFixed(1)} blocks deep`
                        : `Nothing filled: the ground of the zone goes from Y ${+zone.lo.toFixed(1)} to ${+zone.hi.toFixed(1)}`}
                    </p>
                    <button type="button" onClick={clearZone}>Clear the zone</button>
                  </>
                ) : (
                  <p className="ok">Draw the zone first.</p>
                )}
              </>
            )}
            {body.mode === 'river' && <RiverCard body={body} resolved={rivers.get(body.id)?.resolved} node={node} setNode={setNode} setGraph={setGraph} />}
            {body.mode === 'flow' && (
              <>
                <div className="tools">
                  {(['brush', 'eraser'] as Tool[]).map((t) => (
                    <button key={t} type="button" className={tool === t ? 'on' : ''} onClick={() => setTool(t)}>{t}</button>
                  ))}
                </div>
                <Field label={`Brush size ${size}`}><input type="range" min={1} max={60} value={size} onChange={(e) => setSize(Number(e.target.value))} /></Field>
                <p className="hint">
                  Paint the bed freely. The surface follows the banks and slopes down; the arrows show where it drops a block. A free flow can
                  cross the zone of a lake. Red pixels have a surface below the ground.
                </p>
                <Field label="Offset (blocks)"><NumInput value={body.offset} onChange={(v) => updateBody(body.id, { offset: v ?? 0 })} /></Field>
              </>
            )}
            {body.mode === 'raw' && (
              <p className="hint">Read-only: the {body.fluid} map from the imported ZIP, kept with its exact levels. It can only be deleted.</p>
            )}
            {(body.mode === 'flow' || body.mode === 'raw') && (
              <p className="ok">{range ? (range[0] === range[1] ? `Surface at Y ${range[0].toFixed(1)}` : `Surface from Y ${range[0].toFixed(1)} to ${range[1].toFixed(1)}`) : 'Nothing painted yet'}</p>
            )}
            <button type="button" className="danger" onClick={() => { removeBody(body.id); setActive(null); }}>Delete</button>
          </div>
        )}
      </aside>
      <MapView
        drawOverlay={drawOverlay}
        overlayKey={overlayKey}
        overlayOpacity={0.75}
        onDown={down}
        onMove={move}
        onUp={up}
        markers={markers}
        cursor={!body ? 'pointer' : body.mode === 'raw' ? 'not-allowed' : 'crosshair'}
      />
    </>
  );
}
