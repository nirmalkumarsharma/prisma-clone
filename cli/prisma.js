#!/usr/bin/env node
// Usage:
//   node cli/prisma.js <filter|all> <input.png|--sample> [output.png] [--intensity 0.9] [--seed 3]
//   node cli/prisma.js prompt <filter> "<subject>"
//   node cli/prisma.js list
import fs from 'node:fs';
import path from 'node:path';
import { FILTERS, applyFilter, buildPrompt, getFilter } from '../src/filters.js';
import { sampleScene } from '../src/sample.js';
import { decodePNG, encodePNG } from './png.js';

const args = process.argv.slice(2);
const flag = (name, def) => {
  const i = args.indexOf(`--${name}`);
  if (i === -1) return def;
  const v = args[i + 1];
  args.splice(i, 2);
  return v;
};
const intensity = Number(flag('intensity', 1));
const seed = Number(flag('seed', 1));
const [cmd, a, b] = args;

function usage() {
  console.log(`Prisma-style filters: ${FILTERS.map((f) => f.id).join(', ')}

  node cli/prisma.js <filter|all> <input.png|--sample> [output.png] [--intensity 0..1] [--seed n]
  node cli/prisma.js prompt <filter> "<subject>"
  node cli/prisma.js list`);
}

if (!cmd || cmd === 'help' || cmd === '--help') {
  usage();
} else if (cmd === 'list') {
  for (const f of FILTERS) console.log(`${f.id.padEnd(8)} ${f.name.padEnd(8)} ${f.tagline}`);
} else if (cmd === 'prompt') {
  if (!getFilter(a)) { console.error(`Unknown filter "${a}".`); usage(); process.exit(1); }
  console.log(buildPrompt(a, b));
} else {
  const ids = cmd === 'all' ? FILTERS.map((f) => f.id) : [cmd];
  if (!ids.every(getFilter)) { console.error(`Unknown filter "${cmd}".`); usage(); process.exit(1); }
  const src = !a || a === '--sample' ? sampleScene(800, 600) : decodePNG(fs.readFileSync(a));
  const base = a && a !== '--sample' ? path.basename(a, path.extname(a)) : 'sample';
  for (const id of ids) {
    const out = b && ids.length === 1 ? b : `${base}-${id}.png`;
    const t0 = Date.now();
    fs.writeFileSync(out, encodePNG(applyFilter(id, src, { intensity, seed })));
    console.log(`${id.padEnd(8)} → ${out} (${Date.now() - t0} ms)`);
  }
}
