// Builds the web versions of the landing page screenshots.
//
//   npm run screenshots -- <folder with screen1.png, screen2.png, ...>
//
// For each screenN.png, writes public/screenshots/screenN.webp (full size, shown when the
// screenshot is opened) and screenN-thumb.webp (the gallery thumbnail).
import { readdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import sharp from 'sharp';

const source = process.argv[2];
if (!source) {
  console.error('Usage: npm run screenshots -- <folder with screenN.png files>');
  process.exit(1);
}
const out = resolve(import.meta.dirname, '../public/screenshots');
const files = (await readdir(source)).filter((f) => /^screen\d+\.(png|jpe?g)$/i.test(f)).sort();
if (files.length === 0) {
  console.error(`No screenN.png found in ${source}`);
  process.exit(1);
}

for (const file of files) {
  const name = file.replace(/\.[^.]+$/, '');
  const input = sharp(join(source, file));
  const full = await input.clone().resize({ width: 1920, withoutEnlargement: true }).webp({ quality: 82 }).toFile(join(out, `${name}.webp`));
  const thumb = await input.clone().resize({ width: 800, withoutEnlargement: true }).webp({ quality: 78 }).toFile(join(out, `${name}-thumb.webp`));
  console.log(`${name}: full ${(full.size / 1024).toFixed(0)} KB (${full.width}x${full.height}), thumb ${(thumb.size / 1024).toFixed(0)} KB`);
}
