import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { resolveHeightMapping, toY } from '../lib/heightMapping';
import { useStore } from './store';

export interface Pt {
  x: number;
  z: number;
}

interface Props {
  /** Fills RGBA pixels of the section overlay (width*height*4). Called again when `overlayKey` changes. */
  drawOverlay?: (pixels: Uint8ClampedArray, width: number, height: number) => void;
  overlayKey?: unknown;
  overlayOpacity?: number;
  onDown?: (p: Pt, e: React.PointerEvent) => void;
  onMove?: (p: Pt, e: React.PointerEvent) => void;
  onUp?: () => void;
  /** SVG content in pixel coordinates (1 unit = 1 pixel). */
  markers?: ReactNode;
  cursor?: string;
}

export function MapView({ drawOverlay, overlayKey, overlayOpacity = 0.7, onDown, onMove, onUp, markers, cursor = 'crosshair' }: Props) {
  const project = useStore((s) => s.project);
  const rev = useStore((s) => s.rev);
  const hm = project.heightmap;
  const box = useRef<HTMLDivElement>(null);
  const baseCanvas = useRef<HTMLCanvasElement>(null);
  const overlayCanvas = useRef<HTMLCanvasElement>(null);
  const [view, setView] = useState({ x: 0, y: 0, k: 1 });
  const [hover, setHover] = useState<Pt | null>(null);
  const [refUrls, setRefUrls] = useState<Map<Uint8Array, string>>(new Map());
  const focus = useStore((s) => s.focus);
  const pan = useRef<{ sx: number; sy: number; vx: number; vy: number } | null>(null);
  const space = useRef(false);

  const mapping = useMemo(() => resolveHeightMapping(project.config.height as never, hm?.bitDepth ?? 8), [project.config.height, hm?.bitDepth]);

  // Heightmap layer
  useEffect(() => {
    const c = baseCanvas.current;
    if (!c || !hm) return;
    c.width = hm.width;
    c.height = hm.height;
    const ctx = c.getContext('2d')!;
    const img = ctx.createImageData(hm.width, hm.height);
    const shift = hm.bitDepth === 16 ? 8 : 0;
    for (let i = 0; i < hm.data.length; i++) {
      const v = hm.data[i] >> shift;
      img.data[i * 4] = img.data[i * 4 + 1] = img.data[i * 4 + 2] = v;
      img.data[i * 4 + 3] = 255;
    }
    ctx.putImageData(img, 0, 0);
  }, [hm]);

  // Fit the map when it changes
  useEffect(() => {
    if (!hm || !box.current) return;
    const r = box.current.getBoundingClientRect();
    const k = Math.min(r.width / hm.width, r.height / hm.height) * 0.95;
    setView({ k, x: (r.width - hm.width * k) / 2, y: (r.height - hm.height * k) / 2 });
  }, [hm?.width, hm?.height]); // eslint-disable-line react-hooks/exhaustive-deps

  // Reference images: one object URL per image, released when the image is removed
  useEffect(() => {
    setRefUrls((old) => {
      const next = new Map<Uint8Array, string>();
      for (const r of project.references) {
        next.set(r.bytes, old.get(r.bytes) ?? URL.createObjectURL(new Blob([r.bytes as BlobPart], { type: r.mime })));
      }
      for (const [bytes, url] of old) if (!next.has(bytes)) URL.revokeObjectURL(url);
      return next;
    });
  }, [project.references]);

  // Center on a pixel when asked (e.g. from a list)
  useEffect(() => {
    if (!focus || !hm || !box.current) return;
    const r = box.current.getBoundingClientRect();
    setView((v) => {
      const k = Math.max(v.k, 4);
      return { k, x: r.width / 2 - (focus.x + 0.5) * k, y: r.height / 2 - (focus.z + 0.5) * k };
    });
  }, [focus?.n]); // eslint-disable-line react-hooks/exhaustive-deps

  // Section overlay, redrawn on the next frame after a change
  useEffect(() => {
    const c = overlayCanvas.current;
    if (!c || !hm || !drawOverlay) return;
    const frame = requestAnimationFrame(() => {
      c.width = hm.width;
      c.height = hm.height;
      const ctx = c.getContext('2d')!;
      const img = ctx.createImageData(hm.width, hm.height);
      drawOverlay(img.data, hm.width, hm.height);
      ctx.putImageData(img, 0, 0);
    });
    return () => cancelAnimationFrame(frame);
  }, [hm, rev, overlayKey]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const down = (e: KeyboardEvent) => { if (e.code === 'Space' && !(e.target instanceof HTMLInputElement) && !(e.target instanceof HTMLTextAreaElement)) { space.current = true; e.preventDefault(); } };
    const up = (e: KeyboardEvent) => { if (e.code === 'Space') space.current = false; };
    window.addEventListener('keydown', down);
    window.addEventListener('keyup', up);
    return () => { window.removeEventListener('keydown', down); window.removeEventListener('keyup', up); };
  }, []);

  const toPixel = useCallback((e: { clientX: number; clientY: number }): Pt | null => {
    if (!hm || !box.current) return null;
    const r = box.current.getBoundingClientRect();
    const x = Math.floor((e.clientX - r.left - view.x) / view.k);
    const z = Math.floor((e.clientY - r.top - view.y) / view.k);
    return { x, z };
  }, [hm, view]);

  const inside = (p: Pt | null): p is Pt => !!p && !!hm && p.x >= 0 && p.z >= 0 && p.x < hm.width && p.z < hm.height;

  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const wheel = (e: WheelEvent) => {
      e.preventDefault();
      const r = el.getBoundingClientRect();
      const mx = e.clientX - r.left;
      const my = e.clientY - r.top;
      setView((v) => {
        const k = Math.max(0.05, Math.min(64, v.k * Math.exp(-e.deltaY * 0.0015)));
        return { k, x: mx - ((mx - v.x) / v.k) * k, y: my - ((my - v.y) / v.k) * k };
      });
    };
    el.addEventListener('wheel', wheel, { passive: false });
    return () => el.removeEventListener('wheel', wheel);
  }, []);

  const pointerDown = (e: React.PointerEvent) => {
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    if (e.button === 1 || e.button === 2 || space.current) {
      pan.current = { sx: e.clientX, sy: e.clientY, vx: view.x, vy: view.y };
      return;
    }
    const p = toPixel(e);
    if (p && onDown) onDown(p, e);
  };
  const pointerMove = (e: React.PointerEvent) => {
    if (pan.current) {
      const s = pan.current;
      setView((v) => ({ ...v, x: s.vx + e.clientX - s.sx, y: s.vy + e.clientY - s.sy }));
      return;
    }
    const p = toPixel(e);
    setHover(inside(p) ? p : null);
    if (p && e.buttons & 1 && onMove) onMove(p, e);
  };
  const pointerUp = () => {
    if (pan.current) pan.current = null;
    else onUp?.();
  };

  if (!hm) return <div className="empty">Upload a heightmap in the General section first.</div>;

  const raw = hover ? hm.data[hover.z * hm.width + hover.x] : null;
  const scale = (project.config.horizontal_scale as number | undefined) ?? 1;
  const origin = (project.config.origin as number[] | undefined) ?? [-Math.floor((hm.width * scale) / 2), -Math.floor((hm.height * scale) / 2)];

  return (
    <div className="mapview">
      <div
        ref={box}
        className="mapbox"
        style={{ cursor: space.current ? 'grab' : cursor }}
        onPointerDown={pointerDown}
        onPointerMove={pointerMove}
        onPointerUp={pointerUp}
        onPointerLeave={() => setHover(null)}
        onContextMenu={(e) => e.preventDefault()}
      >
        <div className="mapstack" style={{ width: hm.width, height: hm.height, transform: `translate(${view.x}px, ${view.y}px) scale(${view.k})` }}>
          <canvas ref={baseCanvas} />
          {project.references.map((r) => r.visible && refUrls.get(r.bytes) && (
            <img key={r.id} src={refUrls.get(r.bytes)} alt="" draggable={false} style={{ opacity: r.opacity }} />
          ))}
          {drawOverlay && <canvas ref={overlayCanvas} style={{ opacity: overlayOpacity }} />}
          <svg width={hm.width} height={hm.height} viewBox={`0 0 ${hm.width} ${hm.height}`}>{markers}</svg>
        </div>
      </div>
      <div className="statusbar">
        {hover && raw !== null ? (
          <>
            px {hover.x}, {hover.z} · value {raw} · Y {toY(mapping, raw).toFixed(1)} · world {origin[0] + Math.floor((hover.x + 0.5) * scale)}, {origin[1] + Math.floor((hover.z + 0.5) * scale)}
          </>
        ) : (
          'Wheel: zoom · Right/middle button or Space+drag: pan'
        )}
        <span className="zoom">{Math.round(view.k * 100)}%</span>
      </div>
    </div>
  );
}
