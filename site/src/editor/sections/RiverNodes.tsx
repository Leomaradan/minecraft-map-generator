import type { ReactNode } from 'react';
import type { FluidBody, RiverNode } from '../../lib/project';
import { DEFAULT_DROP, nodeType, removeNode, updateNode, type ResolvedRiver } from '../../lib/rivers';
import { Field, NumInput } from '../ui';

type Graph = Pick<FluidBody, 'nodes' | 'edges'>;

/** SVG path of an arrow of length `len` centered on (x, z), pointing along the unit vector (ux, uz). */
export function arrowPath(x: number, z: number, ux: number, uz: number, len: number): string {
  const tx = x + (ux * len) / 2;
  const tz = z + (uz * len) / 2;
  const head = len * 0.4;
  return `M${x - (ux * len) / 2},${z - (uz * len) / 2}L${tx},${tz}`
    + `M${tx - head * (ux - uz * 0.6)},${tz - head * (uz + ux * 0.6)}L${tx},${tz}L${tx - head * (ux + uz * 0.6)},${tz - head * (uz - ux * 0.6)}`;
}

const fmt = (v: number) => String(+v.toFixed(1));

/** Center lines, flow arrows and (for the selected river) nodes, in map pixels. Nodes sit on pixel centers. */
export function riverMarkers(body: FluidBody, r: ResolvedRiver, k: number, selected: boolean, node: number | null, num: number | undefined): ReactNode[] {
  const out: ReactNode[] = [];
  const nodes = body.nodes ?? [];
  const at = new Map(nodes.map((n, i) => [n.id, { n, i }]));
  const c = (n: RiverNode) => [n.x + 0.5, n.z + 0.5] as const;
  r.links.forEach(([a, b], e) => {
    const [ax, az] = c(at.get(a)!.n);
    const [bx, bz] = c(at.get(b)!.n);
    out.push(<line key={`l${body.id}-${e}`} x1={ax} y1={az} x2={bx} y2={bz} className={selected ? 'riverline on' : 'riverline'} style={{ strokeWidth: (selected ? 1.5 : 1) / k }} />);
    const [la, lb] = r.edgeLevels[e];
    const len = Math.hypot(bx - ax, bz - az);
    if (Math.abs(la - lb) < 0.25 || len < 1) return;
    const sign = la > lb ? 1 : -1;
    const d = arrowPath((ax + bx) / 2, (az + bz) / 2, (sign * (bx - ax)) / len, (sign * (bz - az)) / len, Math.min(len * 0.5, 14 / k));
    out.push(<path key={`f${body.id}-${e}`} d={d} className="flow" style={{ strokeWidth: 1.6 / k }} />);
  });
  if (selected) {
    for (const n of nodes) {
      const [x, z] = c(n);
      const type = nodeType(body, n.id);
      const cls = `rivernode ${type}${n.id === node ? ' on' : ''}`;
      const s = 5 / k;
      const style = { strokeWidth: 1.5 / k };
      if (type === 'leaf') out.push(<circle key={`n${n.id}`} cx={x} cy={z} r={s} className={cls} style={style} />);
      else if (type === 'intermediate') out.push(<circle key={`n${n.id}`} cx={x} cy={z} r={s * 0.7} className={cls} style={style} />);
      else if (type === 'connector') out.push(<rect key={`n${n.id}`} x={x - s} y={z - s} width={2 * s} height={2 * s} transform={`rotate(45 ${x} ${z})`} className={cls} style={style} />);
      else {
        // Triangle pointing to the link below the waterfall
        const i = at.get(n.id)!.i;
        let ux = 0;
        let uz = 1;
        r.links.forEach(([a, b], e) => {
          const end = a === n.id ? r.edgeLevels[e][0] : b === n.id ? r.edgeLevels[e][1] : NaN;
          if (!(end < r.level[i] - 0.01)) return;
          const o = at.get(a === n.id ? b : a)!.n;
          const l = Math.hypot(o.x - n.x, o.z - n.z) || 1;
          ux = (o.x - n.x) / l;
          uz = (o.z - n.z) / l;
        });
        const t = s * 1.5;
        const pts = [[x + ux * t, z + uz * t], [x - ux * t * 0.6 - uz * t, z - uz * t * 0.6 + ux * t], [x - ux * t * 0.6 + uz * t, z - uz * t * 0.6 - ux * t]];
        out.push(<polygon key={`n${n.id}`} points={pts.map((p) => p.join(',')).join(' ')} className={cls} style={style} />);
      }
    }
  }
  if (num !== undefined && nodes.length > 0) {
    const [x, z] = c(nodes[0]);
    out.push(<text key={`t${body.id}`} x={x} y={z - 12 / k} className={selected ? 'bodynum on' : 'bodynum'} style={{ fontSize: 13 / k, strokeWidth: 3 / k }}>{num}</text>);
  }
  return out;
}

interface CardProps {
  body: FluidBody;
  resolved: ResolvedRiver | undefined;
  node: number | null;
  setNode: (id: number | null) => void;
  setGraph: (g: Graph) => void;
}

export function RiverCard({ body, resolved, node, setNode, setGraph }: CardProps) {
  const nodes = body.nodes ?? [];
  const i = nodes.findIndex((n) => n.id === node);
  const n = i >= 0 ? nodes[i] : null;
  const levels = resolved ? [...resolved.level, ...resolved.edgeLevels.flat()] : [];
  const set = (patch: Partial<RiverNode>) => n && setGraph(updateNode(body, n.id, patch));
  return (
    <>
      <p className="hint">
        Click the map to add a node linked to the selected one, or click a link to insert a node in it. Drag a node to move it. Shift+click
        a node to link or unlink it from the selected one. Esc deselects the node, to start a new branch; Delete removes it. A radius or a
        surface left empty is interpolated between the nodes that have one. The {body.fluid} fills the river wherever the ground is below the
        surface.
      </p>
      <p className="ok">
        {nodes.length} node(s), {resolved?.links.length ?? 0} link(s)
        {levels.length > 0 && ` · surface from Y ${fmt(Math.min(...levels))} to ${fmt(Math.max(...levels))}`}
      </p>
      {n && resolved && (
        <div className="card">
          <h4>Node {i + 1}</h4>
          <Field label="Type" hint="Leaf, intermediate and connector follow from the number of links">
            <select value={n.waterfall ? 'waterfall' : 'auto'} onChange={(e) => set({ waterfall: e.target.value === 'waterfall' ? true : undefined, drop: undefined })}>
              <option value="auto">{nodeType(body, n.id, true)}</option>
              <option value="waterfall">waterfall</option>
            </select>
          </Field>
          <Field label="Radius (map pixels, empty: interpolated)">
            <NumInput value={n.radius} min={0.5} step={0.5} placeholder={fmt(resolved.radius[i])} onChange={(v) => set({ radius: v })} />
          </Field>
          <Field label={n.waterfall ? 'Surface Y at the top (empty: interpolated)' : 'Surface Y (empty: interpolated)'}>
            <NumInput value={n.level} placeholder={fmt(resolved.level[i])} onChange={(v) => set({ level: v })} />
          </Field>
          {n.waterfall && (
            <Field label="Height of the waterfall (blocks)">
              <NumInput value={n.drop} min={0} placeholder={String(DEFAULT_DROP)} onChange={(v) => set({ drop: v })} />
            </Field>
          )}
          <button type="button" className="danger" onClick={() => { setGraph(removeNode(body, n.id)); setNode(null); }}>Delete the node</button>
        </div>
      )}
    </>
  );
}
