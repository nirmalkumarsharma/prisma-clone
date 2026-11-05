import { FILTERS, buildPrompt } from './filters.js';
import { Cancelled, FilterRunner } from './runner.js';
import { sampleScene } from './sample.js';

const MAX_SIDE = 1200; // filtering runs in a worker; this caps per-filter time at a few seconds
const THUMB = 140;

const $ = (id) => document.getElementById(id);
const canvas = $('canvas');
const ctx = canvas.getContext('2d');

const state = {
  source: null, // ImageData-like at working resolution
  styled: null,
  filter: FILTERS[0].id,
  intensity: 1,
  seed: 1,
};

const mainRunner = new FilterRunner();
const thumbRunner = new FilterRunner();

function toImageData(img) {
  return img instanceof ImageData ? img : new ImageData(img.data, img.width, img.height);
}

function fitCanvasFromBitmap(bitmap, maxSide) {
  const k = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
  const w = Math.max(1, Math.round(bitmap.width * k));
  const h = Math.max(1, Math.round(bitmap.height * k));
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const cx = c.getContext('2d');
  cx.drawImage(bitmap, 0, 0, w, h);
  return cx.getImageData(0, 0, w, h);
}

function draw(img) {
  canvas.width = img.width;
  canvas.height = img.height;
  ctx.putImageData(toImageData(img), 0, 0);
}

async function render() {
  if (!state.source) return;
  mainRunner.cancel();
  $('busy').hidden = false;
  try {
    const out = await mainRunner.run(state.filter, state.source, { intensity: state.intensity, seed: state.seed });
    state.styled = out;
    draw(out);
    $('busy').hidden = true;
  } catch (err) {
    if (err instanceof Cancelled) return; // a newer render took over
    $('busy').hidden = true;
    console.error(err);
  }
}

function renderThumbs() {
  if (!state.source) return;
  thumbRunner.cancel();
  const full = document.createElement('canvas');
  full.width = state.source.width;
  full.height = state.source.height;
  full.getContext('2d').putImageData(toImageData(state.source), 0, 0);
  // Center-square crop for the tiles.
  const side = Math.min(full.width, full.height);
  const sq = document.createElement('canvas');
  sq.width = sq.height = THUMB;
  sq.getContext('2d').drawImage(
    full, (full.width - side) / 2, (full.height - side) / 2, side, side, 0, 0, THUMB, THUMB,
  );
  const small = sq.getContext('2d').getImageData(0, 0, THUMB, THUMB);
  for (const f of FILTERS) {
    thumbRunner.run(f.id, small, { intensity: 1, seed: state.seed }).then((out) => {
      document.querySelector(`[data-filter="${f.id}"] canvas`).getContext('2d').putImageData(toImageData(out), 0, 0);
    }, (err) => { if (!(err instanceof Cancelled)) console.error(err); });
  }
}

function setSource(img) {
  state.source = img;
  state.styled = null;
  render();
  renderThumbs();
}

async function loadFile(file) {
  if (!file || !file.type.startsWith('image/')) return;
  const bitmap = await createImageBitmap(file);
  setSource(fitCanvasFromBitmap(bitmap, MAX_SIDE));
}

function updatePrompt() {
  $('prompt').value = buildPrompt(state.filter, $('subject').value);
}

function selectFilter(id) {
  state.filter = id;
  for (const b of document.querySelectorAll('.filter')) b.setAttribute('aria-pressed', String(b.dataset.filter === id));
  updatePrompt();
  render();
}

function buildFilterStrip() {
  const nav = $('filters');
  for (const f of FILTERS) {
    const b = document.createElement('button');
    b.className = 'filter';
    b.dataset.filter = f.id;
    b.setAttribute('aria-pressed', String(f.id === state.filter));
    const c = document.createElement('canvas');
    c.width = c.height = THUMB;
    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = f.name;
    const tag = document.createElement('span');
    tag.className = 'tag';
    tag.textContent = f.tagline;
    b.append(c, name, tag);
    b.addEventListener('click', () => selectFilter(f.id));
    nav.append(b);
  }
}

function wire() {
  $('file').addEventListener('change', (e) => loadFile(e.target.files[0]));
  $('sample').addEventListener('click', () => setSource(sampleScene(800, 600)));

  let debounce;
  $('intensity').addEventListener('input', (e) => {
    state.intensity = e.target.value / 100;
    $('intensityOut').textContent = `${e.target.value}%`;
    clearTimeout(debounce);
    debounce = setTimeout(render, 120);
  });

  $('reseed').addEventListener('click', () => {
    state.seed = (state.seed % 9973) + 1;
    render();
    renderThumbs();
  });

  const showOriginal = () => state.source && draw(state.source);
  const showStyled = () => state.styled && draw(state.styled);
  const cmp = $('compare');
  cmp.addEventListener('pointerdown', showOriginal);
  for (const ev of ['pointerup', 'pointerleave', 'pointercancel']) cmp.addEventListener(ev, showStyled);
  cmp.addEventListener('keydown', (e) => { if (e.key === ' ' || e.key === 'Enter') showOriginal(); });
  cmp.addEventListener('keyup', showStyled);

  $('download').addEventListener('click', () => {
    if (!state.styled) return;
    draw(state.styled);
    const a = document.createElement('a');
    a.download = `prisma-${state.filter}.png`;
    a.href = canvas.toDataURL('image/png');
    a.click();
  });

  $('subject').addEventListener('input', updatePrompt);
  $('copy').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText($('prompt').value);
      $('copy').textContent = 'Copied!';
    } catch {
      $('prompt').select();
      $('copy').textContent = 'Press Ctrl+C';
    }
    setTimeout(() => { $('copy').textContent = 'Copy prompt'; }, 1500);
  });

  // Drag-and-drop anywhere on the page.
  const stage = $('stage');
  window.addEventListener('dragover', (e) => { e.preventDefault(); stage.classList.add('dragging'); });
  window.addEventListener('dragleave', (e) => { if (!e.relatedTarget) stage.classList.remove('dragging'); });
  window.addEventListener('drop', (e) => {
    e.preventDefault();
    stage.classList.remove('dragging');
    loadFile(e.dataTransfer.files[0]);
  });
  window.addEventListener('paste', (e) => {
    const item = [...(e.clipboardData?.items || [])].find((i) => i.type.startsWith('image/'));
    if (item) loadFile(item.getAsFile());
  });
}

buildFilterStrip();
wire();
updatePrompt();
setSource(sampleScene(800, 600));
