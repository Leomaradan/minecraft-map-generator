import { useEffect, useState } from 'react';

const BASE = import.meta.env.BASE_URL;
const REPO = 'https://github.com/Leomaradan/minecraft-map-generator';
const RELEASE = `${REPO}/releases/latest`;

const FEATURES = [
  ['Heightmap', '8-bit or 16-bit grayscale PNG. Every pixel is the height of the ground, up to 700+ blocks tall.'],
  ['Biomes', 'Paint a color per biome: vanilla, modded or custom. Rivers, mountains and borders can swap biomes.'],
  ['Water and lava', 'Fluid surface maps for lakes, seas and lava pools, with sloped rivers.'],
  ['Structures', 'Place structure templates (.nbt) or registered structures at exact coordinates.'],
  ['Block mixes', 'Surface blocks as weighted lists, random or Perlin patches.'],
  ['Datapacks', 'Embed a datapack in the ZIP for custom biomes, features and loot.'],
];

const SCREENSHOTS = [1, 2, 3, 4];
const screenshot = (n: number) => `${BASE}screenshots/screen${n}.png`;

export function Landing() {
  const [open, setOpen] = useState<number | null>(null);

  useEffect(() => {
    if (open === null) return;
    const step = (d: number) => setOpen((i) => (i === null ? null : (i + d + SCREENSHOTS.length) % SCREENSHOTS.length));
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(null);
      else if (e.key === 'ArrowLeft') step(-1);
      else if (e.key === 'ArrowRight') step(1);
    };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, [open]);

  return (
    <>
      <header className="hero">
        <h1>Map Generator</h1>
        <p>Build a Minecraft world from a picture. A heightmap, a biome map and a structure list, packed in a ZIP.</p>
        <div className="buttons">
          <a className="btn primary" href={RELEASE}>Download the mod</a>
          <a className="btn" href={`${BASE}editor/`}>Open the map editor</a>
        </div>
        <small>Minecraft 26.3 · Fabric and NeoForge</small>
      </header>

      <main>
        <section>
          <h2>What it does</h2>
          <p>
            The mod adds a <strong>Map from ZIP</strong> world type. Instead of procedural generation, the world follows your
            map: the terrain, the biomes, the lakes and the buildings are where you put them.
          </p>
          <div className="grid">
            {FEATURES.map(([title, text]) => (
              <article key={title}>
                <h3>{title}</h3>
                <p>{text}</p>
              </article>
            ))}
          </div>
        </section>

        <section>
          <h2>Screenshots</h2>
          <div className="gallery">
            {SCREENSHOTS.map((n, i) => (
              <button key={n} type="button" className="thumb" onClick={() => setOpen(i)} aria-label={`Enlarge screenshot ${n}`}>
                <img src={screenshot(n)} alt={`Screenshot ${n}`} loading="lazy" />
              </button>
            ))}
          </div>
        </section>

        <section>
          <h2>How it works</h2>
          <ol>
            <li>Draw your maps, or use the <a href={`${BASE}editor/`}>online editor</a> to paint them.</li>
            <li>Pack them in a ZIP (<code>map.json</code>, <code>heightmap.png</code>, <code>biomes.png</code>…).</li>
            <li>Create New World → World Type: Map from ZIP → Customize, then pick the ZIP.</li>
          </ol>
          <p>
            The complete format is described in the{' '}
            <a href={`${REPO}/blob/main/docs/FORMAT.md`}>format documentation</a>.
          </p>
        </section>

        <section id="download">
          <h2>Download</h2>
          <p>
            Get the jar for your loader from the <a href={RELEASE}>latest release</a>: <code>mapgen-fabric</code> (needs Fabric
            API) or <code>mapgen-neoforge</code>.
          </p>
          <h3>Example maps</h3>
          <p>Ready-made ZIPs to try the mod, or to import in the editor and modify.</p>
          <ul>
            <li><a href={`${BASE}examples/example.zip`} download>example.zip</a>: 256×256 island, 8-bit maps</li>
            <li><a href={`${BASE}examples/example-tall.zip`} download>example-tall.zip</a>: 16-bit maps, 700-block mountain</li>
          </ul>
        </section>
      </main>

      {open !== null && (
        <div className="lightbox" role="dialog" aria-modal="true" aria-label="Screenshot" onClick={() => setOpen(null)}>
          <img src={screenshot(SCREENSHOTS[open])} alt={`Screenshot ${SCREENSHOTS[open]}`} onClick={(e) => e.stopPropagation()} />
          <button type="button" className="lb-close" aria-label="Close" onClick={() => setOpen(null)}>×</button>
          <button type="button" className="lb-nav prev" aria-label="Previous" onClick={(e) => { e.stopPropagation(); setOpen((open + SCREENSHOTS.length - 1) % SCREENSHOTS.length); }}>‹</button>
          <button type="button" className="lb-nav next" aria-label="Next" onClick={(e) => { e.stopPropagation(); setOpen((open + 1) % SCREENSHOTS.length); }}>›</button>
        </div>
      )}

      <footer>
        <a href={REPO}>GitHub</a> · <a href={`${BASE}editor/`}>Editor</a>
      </footer>
    </>
  );
}
