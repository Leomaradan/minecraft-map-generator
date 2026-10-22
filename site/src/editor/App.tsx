import { useEffect, useMemo, useRef, useState } from 'react';
import { validateProject } from '../lib/validate';
import { clearDraft, loadDraft, saveDraft } from './autosave';
import { solveAsync } from './workerClient';
import { Biomes } from './sections/Biomes';
import { Fluids } from './sections/Fluids';
import { General } from './sections/General';
import { Structures } from './sections/Structures';
import { useStore, type Section } from './store';

const SECTIONS: { id: Section; label: string }[] = [
  { id: 'general', label: 'General' },
  { id: 'heightmap', label: 'Heightmap' },
  { id: 'biomes', label: 'Biomes' },
  { id: 'structures', label: 'Structures' },
];

function download(bytes: Uint8Array, name: string) {
  const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: 'application/zip' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

export function App() {
  const section = useStore((s) => s.section);
  const setSection = useStore((s) => s.setSection);
  const project = useStore((s) => s.project);
  const rev = useStore((s) => s.rev);
  const importZip = useStore((s) => s.importZipBytes);
  const exportZip = useStore((s) => s.exportZipBytes);
  const reset = useStore((s) => s.reset);
  const doUndo = useStore((s) => s.doUndo);
  const doRedo = useStore((s) => s.doRedo);
  const canUndo = useStore((s) => s.undo.length > 0);
  const canRedo = useStore((s) => s.redo.length > 0);
  const notes = useStore((s) => s.messages);
  const input = useRef<HTMLInputElement>(null);
  const [showMessages, setShowMessages] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [debounced, setDebounced] = useState(0);
  const [lowPixels, setLowPixels] = useState(0);

  // Validation runs shortly after the last change
  useEffect(() => {
    const t = setTimeout(() => setDebounced(rev + Math.random()), 400);
    return () => clearTimeout(t);
  }, [project, rev]);
  // Fluid surfaces below the ground come from the worker
  useEffect(() => {
    const hm = project.heightmap;
    if (!hm || project.bodies.length === 0) {
      setLowPixels(0);
      return;
    }
    let cancelled = false;
    const t = setTimeout(() => {
      solveAsync(project.fluidLabels, project.fluidRaw, project.bodies, hm, project.config.height, rev).then(
        (r) => !cancelled && setLowPixels(r.low.reduce((s, v) => s + v, 0)),
        () => undefined,
      );
    }, 500);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [project, rev]);
  const messages = useMemo(() => {
    try {
      return [...notes, ...validateProject(project, lowPixels)];
    } catch (e) {
      return [{ level: 'error' as const, text: (e as Error).message }];
    }
  }, [debounced, lowPixels]); // eslint-disable-line react-hooks/exhaustive-deps
  const errors = messages.filter((m) => m.level === 'error').length;
  const warnings = messages.length - errors;

  // Restore the draft, then keep it saved
  useEffect(() => {
    loadDraft().then((zip) => {
      if (zip) {
        importZip(zip).catch(() => void clearDraft());
      }
    });
  }, [importZip]);
  useEffect(() => {
    if (!project.heightmap) return;
    let cancelled = false;
    const t = setTimeout(() => {
      exportZip().then((zip) => (cancelled ? undefined : saveDraft(zip)), () => undefined);
    }, 5000);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [project, rev, exportZip]);

  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        if (e.shiftKey) doRedo();
        else doUndo();
      } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') {
        e.preventDefault();
        doRedo();
      }
    };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, [doUndo, doRedo]);

  const onImport = async (file: File) => {
    setError(null);
    try {
      await importZip(new Uint8Array(await file.arrayBuffer()));
      setSection('general');
    } catch (e) {
      setError((e as Error).message);
    }
  };
  const onExport = async () => {
    setError(null);
    try {
      const name = String(project.config.name ?? 'map').replace(/[^A-Za-z0-9_-]+/g, '_') || 'map';
      download(await exportZip(), `${name}.zip`);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <div className="app">
      <header className="topbar">
        <strong>Map editor</strong>
        <button type="button" onClick={() => { if (confirm('Start a new map? Unexported work is lost.')) { reset(); void clearDraft(); } }}>New</button>
        <input ref={input} type="file" accept=".zip" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) void onImport(f); e.target.value = ''; }} />
        <button type="button" onClick={() => input.current?.click()}>Import ZIP…</button>
        <button type="button" className="primary" onClick={onExport} disabled={!project.heightmap}>Export ZIP</button>
        <span className="spacer" />
        <button type="button" onClick={doUndo} disabled={!canUndo} title="Ctrl+Z">Undo</button>
        <button type="button" onClick={doRedo} disabled={!canRedo} title="Ctrl+Y">Redo</button>
        <button type="button" className={errors ? 'badge err' : warnings ? 'badge warn' : 'badge ok'} onClick={() => setShowMessages(!showMessages)}>
          {errors} errors · {warnings} warnings
        </button>
        <a href="../">Home</a>
      </header>
      {error && <div className="banner err">{error} <button type="button" onClick={() => setError(null)}>×</button></div>}
      {showMessages && (
        <div className="messages">
          {messages.length === 0 && <p>No problems found.</p>}
          {messages.map((m, i) => <p key={i} className={m.level === 'error' ? 'err' : 'warn'}>{m.level}: {m.text}</p>)}
        </div>
      )}
      <div className="body">
        <nav className="sidebar">
          {SECTIONS.map((s) => (
            <button key={s.id} type="button" className={section === s.id ? 'on' : ''} onClick={() => setSection(s.id)}>{s.label}</button>
          ))}
        </nav>
        <div className="content">
          {section === 'general' && <General />}
          {section === 'heightmap' && <Fluids />}
          {section === 'biomes' && <Biomes />}
          {section === 'structures' && <Structures />}
        </div>
      </div>
    </div>
  );
}
