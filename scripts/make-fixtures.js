// Writes reference outputs from the JS engine that the Kotlin and Swift ports are tested against.
// Raw RGBA files keep the native tests free of image-decoding dependencies.
import fs from 'node:fs';
import { FILTERS, applyFilter, buildPrompt } from '../src/filters.js';
import { sampleScene } from '../src/sample.js';

const W = 96, H = 72, SEED = 4;
const dir = new URL('../fixtures/', import.meta.url);
fs.mkdirSync(dir, { recursive: true });
const src = sampleScene(W, H);
fs.writeFileSync(new URL('sample.rgba', dir), src.data);
for (const f of FILTERS) {
  fs.writeFileSync(new URL(`${f.id}.rgba`, dir), applyFilter(f.id, src, { seed: SEED }).data);
}
fs.writeFileSync(new URL('candy-60.rgba', dir), applyFilter('candy', src, { seed: SEED, intensity: 0.6 }).data);
const prompts = Object.fromEntries(FILTERS.map((f) => [f.id, buildPrompt(f.id, '  a lighthouse at dusk.  ')]));
prompts.empty = buildPrompt('wave', '   ');
fs.writeFileSync(new URL('fixtures.json', dir), JSON.stringify({ width: W, height: H, seed: SEED, prompts }, null, 2) + '\n');
console.log(`wrote ${FILTERS.length + 2} images + fixtures.json to fixtures/`);
