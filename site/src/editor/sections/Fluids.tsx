import { useEffect, useMemo, useRef, useState } from 'react';
import type { FluidResult } from '../../lib/fluids';
import { fromY, resolveHeightMapping, toY } from '../../lib/heightMapping';
import type { FluidBody } from '../../lib/project';
import { floodFill, stroke } from '../brush';
import { MapView, type Pt } from '../MapView';
import { useStore } from '../store';
import { solveAsync } from '../workerClient';
import { Field, NumInput } from '../ui';

type Tool = 'brush' | 'eraser' | 'fill' | 'pick';

export function bodyColor(b: FluidBody, bodies: FluidBody[]): [number, number, number] {
  const same = bodies.filter((x) => x.fluid === b.fluid);
  const i = same.findIndex((x) => x.id === b.id);
  const shade = (i % 5) * 28;
  return b.fluid === 'water' ? [30 + shade * 0.4, 110 + shade * 0.6, 235 - shade * 0.4] : [235, 90 + shade, 20 + shade * 0.5];
}

const hex = (c: number[]) => `rgb(${c.map((v) => Math.round(v)).join(',')})`;

export function Fluids() {
  const project = useStore((s) => s.project);
  const rev = useStore((s) => s.rev);
  const paint = useStore((s) => s.paint);
  const commit = useStore((s) => s.commitStroke);
  const addBody = useStore((s) => s.addBody);
  const updateBody = useStore((s) => s.updateBody);
  const removeBody = useStore((s) => s.removeBody);
  const hm = project.heightmap;
  const [tool, setTool] = useState<Tool>('brush');
  const [size, setSize] = useState(5);
  const [active, setActive] = useState<number | null>(null);
  const [fillY, setFillY] = useState<number | undefined>(undefined);
  const [result, setResult] = useState<FluidResult | null>(null);
  const last = useRef<Pt | null>(null);
  const mapping = useMemo(() => resolveHeightMapping(project.config.height as never, hm?.bitDepth ?? 8), [project.config.height, hm?.bitDepth]);
  const body = project.bodies.find((b) => b.id === active) ?? null;

  // Computed levels (in a worker), requested shortly after the last change
  useEffect(() => {
    if (!hm || project.bodies.length === 0) {
      setResult(null);
      return;
    }
    let cancelled = false;
    const t = setTimeout(() => {
      solveAsync(project.fluidLabels, project.fluidRaw, project.bodies, hm, project.config.height, rev).then((r) => !cancelled && setResult(r), () => undefined);
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [hm, project.bodies, project.fluidLabels, project.fluidRaw, project.config.height, rev]);

  const range = useMemo(() => {
    if (!result || !body || !hm) return null;
    let lo = Infinity;
    let hi = -Infinity;
    for (let i = 0; i < project.fluidLabels.length; i++) {
      if (project.fluidLabels[i] !== body.id) continue;
      lo = Math.min(lo, result.levels[i]);
      hi = Math.max(hi, result.levels[i]);
    }
    return lo === Infinity ? null : [toY(mapping, lo), toY(mapping, hi)];
  }, [result, body, hm, project.fluidLabels, mapping]);

  const drawOverlay = (px: Uint8ClampedArray, w: number, h: number) => {
    const colors = new Map(project.bodies.map((b) => [b.id, bodyColor(b, project.bodies)]));
    for (let i = 0; i < w * h; i++) {
      const id = project.fluidLabels[i];
      if (id === 0) continue;
      const low = result?.low[i] === 1;
      const c = low ? [255, 0, 60] : (colors.get(id) ?? [128, 128, 128]);
      const a = id === active ? 255 : 200;
      px[i * 4] = c[0];
      px[i * 4 + 1] = c[1];
      px[i * 4 + 2] = c[2];
      px[i * 4 + 3] = a;
    }
  };

  const ensureBody = (): FluidBody | null => {
    if (body) return body;
    return null;
  };

  const down = (p: Pt) => {
    if (!hm) return;
    const idx = p.z * hm.width + p.x;
    if (p.x < 0 || p.z < 0 || p.x >= hm.width || p.z >= hm.height) return;
    if (tool === 'pick') {
      const id = project.fluidLabels[idx];
      if (id) setActive(id);
      return;
    }
    if (tool !== 'eraser') {
      const b = ensureBody();
      if (!b) return;
      if (b.mode === 'raw') updateBody(b.id, { mode: 'lake' });
    }
    if (tool === 'fill') {
      const limit = fillY === undefined ? hm.data[idx] : fromY(mapping, fillY);
      const pixels = floodFill(idx, hm.width, hm.height, (q) => hm.data[q] <= limit && project.fluidLabels[q] === 0);
      paint('fluidLabels', pixels, active!);
      commit();
      return;
    }
    last.current = null;
    move(p);
  };

  const move = (p: Pt) => {
    if (!hm || (tool !== 'brush' && tool !== 'eraser')) return;
    if (tool === 'brush' && !body) return;
    paint('fluidLabels', stroke(last.current, p, size, hm.width, hm.height), tool === 'eraser' ? 0 : body!.id);
    last.current = p;
  };

  const up = () => {
    last.current = null;
    commit();
  };

  const counts = useMemo(() => {
    const c = new Map<number, number>();
    for (let i = 0; i < project.fluidLabels.length; i++) {
      const id = project.fluidLabels[i];
      if (id) c.set(id, (c.get(id) ?? 0) + 1);
    }
    return c;
  }, [project.fluidLabels, rev]); // eslint-disable-line react-hooks/exhaustive-deps
  const used = (id: number) => counts.get(id) ?? 0;

  return (
    <>
      <aside className="panel">
        <h2>Water and lava</h2>
        <div className="tools">
          {(['brush', 'eraser', 'fill', 'pick'] as Tool[]).map((t) => (
            <button key={t} type="button" className={tool === t ? 'on' : ''} onClick={() => setTool(t)}>{t}</button>
          ))}
        </div>
        {(tool === 'brush' || tool === 'eraser') && (
          <Field label={`Brush size ${size}`}><input type="range" min={1} max={60} value={size} onChange={(e) => setSize(Number(e.target.value))} /></Field>
        )}
        {tool === 'fill' && (
          <Field label="Fill pixels with ground up to Y (empty: ground under the click)" hint="Fills the connected area that is not higher than this Y">
            <NumInput value={fillY} onChange={setFillY} />
          </Field>
        )}
        <p className="hint">
          Paint over the terrain: the surface level comes from the ground around each body. A lake is flat at its lowest bank; a river
          follows its banks and slopes. Paint another body for another level, even right next to it. Red pixels have a surface below the ground.
        </p>
        <div className="row">
          <button type="button" onClick={() => setActive(addBody('water').id)}>+ Water</button>
          <button type="button" onClick={() => setActive(addBody('lava').id)}>+ Lava</button>
        </div>
        <ul className="list">
          {project.bodies.map((b) => (
            <li key={b.id} className={b.id === active ? 'active' : ''} onClick={() => setActive(b.id)}>
              <span className="swatch" style={{ background: hex(bodyColor(b, project.bodies)) }} />
              <span>{b.name}</span>
              <small>{used(b.id)} px</small>
            </li>
          ))}
        </ul>
        {body && (
          <div className="card">
            <Field label="Name"><input type="text" value={body.name} onChange={(e) => updateBody(body.id, { name: e.target.value })} /></Field>
            <Field label="Fluid">
              <select value={body.fluid} onChange={(e) => updateBody(body.id, { fluid: e.target.value as FluidBody['fluid'] })}>
                <option value="water">water</option>
                <option value="lava">lava</option>
              </select>
            </Field>
            <Field label="Surface">
              <select value={body.mode} onChange={(e) => updateBody(body.id, { mode: e.target.value as FluidBody['mode'] })}>
                <option value="lake">lake: flat, lowest bank</option>
                <option value="river">river: slope between banks</option>
                {body.mode === 'raw' && <option value="raw">imported: exact levels</option>}
              </select>
            </Field>
            {body.mode !== 'raw' && (
              <>
                <Field label="Fixed surface Y (empty: computed)"><NumInput value={body.levelOverride} onChange={(v) => updateBody(body.id, { levelOverride: v })} /></Field>
                <Field label="Offset (blocks)"><NumInput value={body.offset} onChange={(v) => updateBody(body.id, { offset: v ?? 0 })} /></Field>
              </>
            )}
            <p className="ok">{range ? (range[0] === range[1] ? `Surface at Y ${range[0].toFixed(1)}` : `Surface from Y ${range[0].toFixed(1)} to ${range[1].toFixed(1)}`) : 'Nothing painted yet'}</p>
            <button type="button" className="danger" onClick={() => { removeBody(body.id); setActive(null); }}>Delete body</button>
          </div>
        )}
      </aside>
      <MapView
        drawOverlay={drawOverlay}
        overlayKey={`${active}|${result ? 1 : 0}|${project.bodies.length}`}
        overlayOpacity={0.75}
        onDown={down}
        onMove={move}
        onUp={up}
        cursor={tool === 'pick' ? 'copy' : 'crosshair'}
      />
    </>
  );
}
