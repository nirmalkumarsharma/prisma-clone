// Prisma-style artistic filters.
// Every filter takes an ImageData-like object { width, height, data: Uint8ClampedArray (RGBA) }
// and returns a new one of the same size. No DOM dependency, so this module runs in Node too.

// ---------------------------------------------------------------------------
// Math / noise helpers
// ---------------------------------------------------------------------------

const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);
const mix = (a, b, t) => a + (b - a) * t;
const smoothstep = (e0, e1, x) => {
  const t = clamp01((x - e0) / (e1 - e0));
  return t * t * (3 - 2 * t);
};

function hash2(x, y, seed) {
  let h = Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263) + Math.imul(seed | 0, 1442695041);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967296;
}

function valueNoise(x, y, seed) {
  const xi = Math.floor(x);
  const yi = Math.floor(y);
  const xf = x - xi;
  const yf = y - yi;
  const u = xf * xf * (3 - 2 * xf);
  const v = yf * yf * (3 - 2 * yf);
  const a = hash2(xi, yi, seed);
  const b = hash2(xi + 1, yi, seed);
  const c = hash2(xi, yi + 1, seed);
  const d = hash2(xi + 1, yi + 1, seed);
  return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
}

function fbm(x, y, seed, octaves = 4) {
  let sum = 0;
  let amp = 0.5;
  let f = 1;
  let norm = 0;
  for (let i = 0; i < octaves; i++) {
    sum += amp * valueNoise(x * f, y * f, seed + i * 101);
    norm += amp;
    amp *= 0.5;
    f *= 2;
  }
  return sum / norm;
}

// ---------------------------------------------------------------------------
// Color helpers (all channels 0..1)
// ---------------------------------------------------------------------------

function rgbToHsv(r, g, b) {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const d = max - min;
  let h = 0;
  if (d > 1e-6) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60;
    if (h < 0) h += 360;
  }
  return [h, max === 0 ? 0 : d / max, max];
}

function hsvToRgb(h, s, v) {
  const c = v * s;
  const hp = (((h % 360) + 360) % 360) / 60;
  const x = c * (1 - Math.abs((hp % 2) - 1));
  let r = 0, g = 0, b = 0;
  if (hp < 1) [r, g, b] = [c, x, 0];
  else if (hp < 2) [r, g, b] = [x, c, 0];
  else if (hp < 3) [r, g, b] = [0, c, x];
  else if (hp < 4) [r, g, b] = [0, x, c];
  else if (hp < 5) [r, g, b] = [x, 0, c];
  else [r, g, b] = [c, 0, x];
  const m = v - c;
  return [r + m, g + m, b + m];
}

const hex = (s) => [1, 3, 5].map((i) => parseInt(s.slice(i, i + 2), 16) / 255);

function gradientAt(stops, t) {
  t = clamp01(t);
  for (let i = 1; i < stops.length; i++) {
    if (t <= stops[i][0]) {
      const [t0, c0] = stops[i - 1];
      const [t1, c1] = stops[i];
      const k = (t - t0) / (t1 - t0 || 1);
      return [mix(c0[0], c1[0], k), mix(c0[1], c1[1], k), mix(c0[2], c1[2], k)];
    }
  }
  return stops[stops.length - 1][1].slice();
}

const hueDist = (a, b) => {
  const d = Math.abs(a - b) % 360;
  return d > 180 ? 360 - d : d;
};

// ---------------------------------------------------------------------------
// Image plane helpers
// ---------------------------------------------------------------------------

function toRGB(img) {
  const n = img.width * img.height;
  const out = new Float32Array(n * 3);
  const d = img.data;
  for (let i = 0; i < n; i++) {
    out[i * 3] = d[i * 4] / 255;
    out[i * 3 + 1] = d[i * 4 + 1] / 255;
    out[i * 3 + 2] = d[i * 4 + 2] / 255;
  }
  return out;
}

function toImage(rgb, w, h) {
  const n = w * h;
  const data = new Uint8ClampedArray(n * 4);
  for (let i = 0; i < n; i++) {
    data[i * 4] = Math.round(clamp01(rgb[i * 3]) * 255);
    data[i * 4 + 1] = Math.round(clamp01(rgb[i * 3 + 1]) * 255);
    data[i * 4 + 2] = Math.round(clamp01(rgb[i * 3 + 2]) * 255);
    data[i * 4 + 3] = 255;
  }
  return { width: w, height: h, data };
}

function luminance(rgb, w, h) {
  const n = w * h;
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    out[i] = 0.2126 * rgb[i * 3] + 0.7152 * rgb[i * 3 + 1] + 0.0722 * rgb[i * 3 + 2];
  }
  return out;
}

// Separable box blur (applied twice ≈ triangle/gaussian-ish) on an interleaved plane.
function blur(src, w, h, radius, channels = 3, passes = 2) {
  const r = Math.max(0, Math.round(radius));
  if (r === 0) return Float32Array.from(src);
  let a = Float32Array.from(src);
  let b = new Float32Array(src.length);
  const pass = (from, to, horizontal) => {
    const len = horizontal ? w : h;
    const lines = horizontal ? h : w;
    const norm = 1 / (2 * r + 1);
    for (let line = 0; line < lines; line++) {
      for (let c = 0; c < channels; c++) {
        const idx = (k) => {
          const kk = k < 0 ? 0 : k >= len ? len - 1 : k;
          return ((horizontal ? line * w + kk : kk * w + line) * channels) + c;
        };
        let sum = 0;
        for (let k = -r; k <= r; k++) sum += from[idx(k)];
        for (let k = 0; k < len; k++) {
          to[idx(k)] = sum * norm;
          sum += from[idx(k + r + 1)] - from[idx(k - r)];
        }
      }
    }
  };
  for (let p = 0; p < passes; p++) {
    pass(a, b, true);
    pass(b, a, false);
  }
  return a;
}

function sobel(lum, w, h) {
  const out = new Float32Array(w * h);
  const at = (x, y) => lum[(y < 0 ? 0 : y >= h ? h - 1 : y) * w + (x < 0 ? 0 : x >= w ? w - 1 : x)];
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const gx = -at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1)
        + at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1);
      const gy = -at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1)
        + at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1);
      out[y * w + x] = Math.sqrt(gx * gx + gy * gy);
    }
  }
  return out;
}

// Grayscale max-filter (thickens line masks).
function dilate(mask, w, h, radius) {
  const r = Math.round(radius);
  if (r <= 0) return mask;
  const tmp = new Float32Array(w * h);
  const out = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let m = 0;
      for (let k = -r; k <= r; k++) {
        const xx = Math.min(w - 1, Math.max(0, x + k));
        if (mask[y * w + xx] > m) m = mask[y * w + xx];
      }
      tmp[y * w + x] = m;
    }
  }
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let m = 0;
      for (let k = -r; k <= r; k++) {
        const yy = Math.min(h - 1, Math.max(0, y + k));
        if (tmp[yy * w + x] > m) m = tmp[yy * w + x];
      }
      out[y * w + x] = m;
    }
  }
  return out;
}

// Jittered-grid Voronoi: nearest seed, distance to it, and distance to the cell border.
function voronoi(w, h, cell, seed) {
  const cols = Math.ceil(w / cell) + 1;
  const rows = Math.ceil(h / cell) + 1;
  const sx = new Float32Array(cols * rows);
  const sy = new Float32Array(cols * rows);
  for (let gy = 0; gy < rows; gy++) {
    for (let gx = 0; gx < cols; gx++) {
      const i = gy * cols + gx;
      sx[i] = (gx + 0.1 + 0.8 * hash2(gx, gy, seed)) * cell;
      sy[i] = (gy + 0.1 + 0.8 * hash2(gx, gy, seed + 7)) * cell;
    }
  }
  const index = new Int32Array(w * h);
  const d1 = new Float32Array(w * h);
  const border = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    const gy0 = Math.floor(y / cell);
    for (let x = 0; x < w; x++) {
      const gx0 = Math.floor(x / cell);
      let best = Infinity, second = Infinity, bi = 0;
      for (let oy = -1; oy <= 1; oy++) {
        const gy = gy0 + oy;
        if (gy < 0 || gy >= rows) continue;
        for (let ox = -1; ox <= 1; ox++) {
          const gx = gx0 + ox;
          if (gx < 0 || gx >= cols) continue;
          const i = gy * cols + gx;
          const dx = sx[i] - x, dy = sy[i] - y;
          const d = dx * dx + dy * dy;
          if (d < best) { second = best; best = d; bi = i; } else if (d < second) second = d;
        }
      }
      const p = y * w + x;
      index[p] = bi;
      const a = Math.sqrt(best), b = Math.sqrt(second);
      d1[p] = a;
      border[p] = (b - a) * 0.5; // ≈ distance to the bisector between the two nearest seeds
    }
  }
  return { index, d1, border, sx, sy, count: cols * rows };
}

function cellAverages(rgb, vor, n) {
  const sums = new Float64Array(vor.count * 4);
  for (let p = 0; p < n; p++) {
    const c = vor.index[p] * 4;
    sums[c] += rgb[p * 3];
    sums[c + 1] += rgb[p * 3 + 1];
    sums[c + 2] += rgb[p * 3 + 2];
    sums[c + 3]++;
  }
  const avg = new Float32Array(vor.count * 3);
  for (let i = 0; i < vor.count; i++) {
    const k = sums[i * 4 + 3] || 1;
    avg[i * 3] = sums[i * 4] / k;
    avg[i * 3 + 1] = sums[i * 4 + 1] / k;
    avg[i * 3 + 2] = sums[i * 4 + 2] / k;
  }
  return avg;
}

// Feature size relative to a ~600px reference so filters look the same at any resolution.
const scaleOf = (w, h) => Math.max(w, h) / 600;

// ---------------------------------------------------------------------------
// Filters
// ---------------------------------------------------------------------------

const UDNIE_STOPS = [
  [0.0, hex('#0a1030')], [0.22, hex('#16347a')], [0.42, hex('#1f63b0')],
  [0.58, hex('#3c8ca0')], [0.72, hex('#d29a2e')], [0.88, hex('#f5c85a')], [1.0, hex('#fff2c8')],
];

function udnie(src, { seed = 1 } = {}) {
  const { width: w, height: h } = src;
  const s = scaleOf(w, h);
  const rgb = toRGB(src);

  // Swirling flow field drives both the warp and the brushstroke direction.
  const ang = new Float32Array(w * h);
  const f = 1 / (110 * s);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) ang[y * w + x] = fbm(x * f, y * f, seed, 3) * Math.PI * 4;
  }

  const amp = 7 * s;
  const warped = new Float32Array(w * h * 3);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const a = ang[y * w + x];
      const xx = Math.min(w - 1, Math.max(0, Math.round(x + amp * Math.cos(a))));
      const yy = Math.min(h - 1, Math.max(0, Math.round(y + amp * Math.sin(a))));
      const q = (yy * w + xx) * 3, p = (y * w + x) * 3;
      warped[p] = rgb[q]; warped[p + 1] = rgb[q + 1]; warped[p + 2] = rgb[q + 2];
    }
  }
  const soft = blur(warped, w, h, 2 * s);
  const lum = luminance(soft, w, h);

  // Line-integral convolution of white noise along the flow → streaky impasto strokes.
  const noise = new Float32Array(w * h);
  const cellN = Math.max(1, Math.round(1.5 * s));
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) noise[y * w + x] = hash2((x / cellN) | 0, (y / cellN) | 0, seed + 3);
  }
  const L = Math.max(4, Math.round(9 * s));
  const lic = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let sum = 0, cnt = 0;
      for (const dir of [1, -1]) {
        let px = x, py = y;
        for (let k = 0; k < L; k++) {
          const ix = px | 0, iy = py | 0;
          if (ix < 0 || iy < 0 || ix >= w || iy >= h) break;
          sum += noise[iy * w + ix]; cnt++;
          const a = ang[iy * w + ix];
          px += dir * Math.cos(a); py += dir * Math.sin(a);
        }
      }
      lic[y * w + x] = sum / (cnt || 1);
    }
  }

  const edge = sobel(lum, w, h);
  const out = new Float32Array(w * h * 3);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const p = y * w + x;
      const l = clamp01((lum[p] - 0.5) * 1.45 + 0.5);
      const pal = gradientAt(UDNIE_STOPS, l);
      const [hh, ss, vv] = rgbToHsv(soft[p * 3], soft[p * 3 + 1], soft[p * 3 + 2]);
      const own = hsvToRgb(hh, Math.min(1, ss * 1.6), vv);
      // Emboss lighting from the stroke texture gives raised paint ridges.
      const xa = Math.max(0, x - 1), ya = Math.max(0, y - 1);
      const xb = Math.min(w - 1, x + 1), yb = Math.min(h - 1, y + 1);
      const ridge = (lic[yb * w + xb] - lic[ya * w + xa]) * 2.2;
      const stroke = 0.78 + 0.45 * lic[p] - ridge;
      const ink = 1 - smoothstep(0.25, 0.9, edge[p]) * 0.65;
      for (let c = 0; c < 3; c++) out[p * 3 + c] = mix(pal[c], own[c], 0.28) * stroke * ink;
    }
  }
  return toImage(out, w, h);
}

const WAVE_INKS = [hex('#12142a'), hex('#1c3066'), hex('#345c96'), hex('#8cacc4'), hex('#ece2c8')];

function wave(src, { seed = 1 } = {}) {
  const { width: w, height: h } = src;
  const s = scaleOf(w, h);
  const soft = blur(toRGB(src), w, h, 1.5 * s);
  const lum = luminance(soft, w, h);
  const edge = sobel(blur(lum, w, h, 1 * s, 1), w, h);
  const lines = new Float32Array(w * h);
  for (let p = 0; p < w * h; p++) lines[p] = smoothstep(0.18, 0.32, edge[p]);
  const thick = dilate(lines, w, h, 0.8 * s);

  const out = new Float32Array(w * h * 3);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const p = y * w + x;
      const l = clamp01((lum[p] - 0.5) * 1.3 + 0.5);
      const level = l < 0.2 ? 0 : l < 0.4 ? 1 : l < 0.6 ? 2 : l < 0.8 ? 3 : 4;
      let col = WAVE_INKS[level];
      const [hh, ss, vv] = rgbToHsv(soft[p * 3], soft[p * 3 + 1], soft[p * 3 + 2]);
      // Woodblock prints kept a few warm accents: vermilion and ochre.
      if (ss > 0.35 && vv > 0.3 && (hh < 55 || hh > 330)) {
        col = level <= 2 ? hex('#b4523a') : hex('#d4aa62');
      }
      // Wood grain in flat regions, and fibrous washi paper everywhere.
      const grain = 1 + 0.045 * Math.sin(x * 0.09 / s + fbm(x / (40 * s), y / (8 * s), seed, 2) * 14);
      const paper = 0.9 + 0.1 * fbm(x / (2 * s), y / (30 * s), seed + 5, 3);
      const speck = hash2(x, y, seed + 9) > 0.995 ? 0.85 : 1;
      const k = grain * paper * speck;
      const t = thick[p];
      for (let c = 0; c < 3; c++) out[p * 3 + c] = mix(col[c] * k, WAVE_INKS[0][c], t);
    }
  }
  return toImage(out, w, h);
}

function mosaic(src, { seed = 1 } = {}) {
  const { width: w, height: h } = src;
  const s = scaleOf(w, h);
  const n = w * h;
  const rgb = toRGB(src);
  const cell = Math.max(5, Math.round(13 * s));
  const vor = voronoi(w, h, cell, seed);
  const avg = cellAverages(rgb, vor, n);
  const grout = 1.1 * s + 0.6;
  const groutCol = hex('#2a2622');

  const out = new Float32Array(n * 3);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const p = y * w + x;
      const ci = vor.index[p];
      let [hh, ss, vv] = rgbToHsv(avg[ci * 3], avg[ci * 3 + 1], avg[ci * 3 + 2]);
      ss = Math.min(1, ss * 1.45 + 0.05);
      vv = clamp01((vv - 0.5) * 1.25 + 0.52);
      const jitter = 0.92 + 0.16 * hash2(ci, 0, seed + 11);
      const tile = hsvToRgb(hh + (hash2(ci, 1, seed) - 0.5) * 10, ss, vv);
      // Domed tile: lit from top-left, darker toward the grout.
      const dx = (x - vor.sx[ci]) / cell, dy = (y - vor.sy[ci]) / cell;
      const bevel = 1 - 0.28 * (dx + dy) - 0.15 * smoothstep(0.4, 0.9, vor.d1[p] / cell);
      const grit = 0.95 + 0.1 * hash2(x, y, seed + 13);
      const g = 1 - smoothstep(grout * 0.6, grout + 0.8, vor.border[p]);
      for (let c = 0; c < 3; c++) {
        out[p * 3 + c] = mix(tile[c] * jitter * bevel * grit, groutCol[c] * grit, g);
      }
    }
  }
  return toImage(out, w, h);
}

function candy(src, { seed = 1 } = {}) {
  const { width: w, height: h } = src;
  const s = scaleOf(w, h);
  const soft = blur(toRGB(src), w, h, 2 * s);
  const lum = luminance(soft, w, h);
  const edge = sobel(lum, w, h);
  const lines = new Float32Array(w * h);
  for (let p = 0; p < w * h; p++) lines[p] = smoothstep(0.12, 0.24, edge[p]);
  const thick = dilate(lines, w, h, 1.2 * s);
  const outline = hex('#2a0a3c');
  const dot = 6 * s + 2;

  const out = new Float32Array(w * h * 3);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const p = y * w + x;
      let [hh, ss, vv] = rgbToHsv(soft[p * 3], soft[p * 3 + 1], soft[p * 3 + 2]);
      // Push hues toward a neon wheel: hot pink, tangerine, lemon, lime, cyan, violet.
      hh = Math.round(hh / 30) * 30 + 8 * Math.sin(hh * 0.05 + seed);
      ss = Math.min(1, Math.round((ss * 1.9 + 0.25) * 3) / 3);
      vv = Math.min(1, Math.round((vv * 1.2 + 0.08) * 4) / 4);
      if (ss < 0.3) hh = 300 + 40 * vv; // neutral areas become candy pink/violet
      ss = Math.max(ss, 0.55);
      let col = hsvToRgb(hh, ss, Math.max(vv, 0.25));
      // Comic halftone dots in the shadows.
      if (vv < 0.62) {
        const u = (x + y) / (dot * 1.414), v = (x - y) / (dot * 1.414);
        const fx = u - Math.floor(u) - 0.5, fy = v - Math.floor(v) - 0.5;
        const r = Math.sqrt(fx * fx + fy * fy);
        const radius = 0.15 + 0.5 * (0.62 - vv);
        const inDot = 1 - smoothstep(radius - 0.05, radius + 0.05, r);
        col = col.map((c, i) => mix(c, outline[i], inDot * 0.7));
      }
      const t = thick[p];
      for (let c = 0; c < 3; c++) out[p * 3 + c] = mix(col[c], outline[c], t);
    }
  }
  return toImage(out, w, h);
}

const JEWELS = [
  [350, hex('#9e0f2a')], [28, hex('#c8650f')], [48, hex('#e0aa1a')], [130, hex('#0f7a3c')],
  [178, hex('#0f8a8c')], [218, hex('#123a9e')], [265, hex('#4a1e8c')], [300, hex('#7a1a6e')],
];

function stainedGlass(src, { seed = 1 } = {}) {
  const { width: w, height: h } = src;
  const s = scaleOf(w, h);
  const n = w * h;
  const rgb = toRGB(src);
  const cell = Math.max(10, Math.round(30 * s));
  const vor = voronoi(w, h, cell, seed);
  // Split every Voronoi cell by tonal band so glass pieces follow the subject's contours.
  const BANDS = 5;
  const tone = luminance(blur(rgb, w, h, 3.5 * s), w, h);
  const seg = new Int32Array(n);
  for (let p = 0; p < n; p++) seg[p] = vor.index[p] * BANDS + Math.min(BANDS - 1, (tone[p] * BANDS) | 0);
  const segCount = vor.count * BANDS;
  const sums = new Float64Array(segCount * 4);
  for (let p = 0; p < n; p++) {
    const k = seg[p] * 4;
    sums[k] += rgb[p * 3]; sums[k + 1] += rgb[p * 3 + 1]; sums[k + 2] += rgb[p * 3 + 2]; sums[k + 3]++;
  }
  // Lead came: Voronoi borders plus every boundary between pieces.
  const split = new Float32Array(n);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const p = y * w + x;
      if ((x + 1 < w && seg[p + 1] !== seg[p]) || (y + 1 < h && seg[p + w] !== seg[p])) split[p] = 1;
    }
  }
  const splitLead = blur(dilate(split, w, h, 0.9 * s), w, h, 0.6, 1, 1);
  const lead = 2.2 * s + 1;
  const leadCol = hex('#121014');

  const glass = new Float32Array(segCount * 3);
  for (let i = 0; i < segCount; i++) {
    const k = sums[i * 4 + 3];
    if (!k) continue;
    const [hh, ss, vv] = rgbToHsv(sums[i * 4] / k, sums[i * 4 + 1] / k, sums[i * 4 + 2] / k);
    let col;
    if (ss < 0.12) {
      // Unsaturated pieces: cathedral "clear" glass, pale amber or smoky blue.
      col = vv > 0.55 ? hex('#e8dcae') : hex('#3c4a6e');
    } else {
      let best = JEWELS[0];
      for (const j of JEWELS) if (hueDist(j[0], hh) < hueDist(best[0], hh)) best = j;
      col = best[1];
    }
    const bright = 0.55 + vv * 0.9;
    glass[i * 3] = col[0] * bright; glass[i * 3 + 1] = col[1] * bright; glass[i * 3 + 2] = col[2] * bright;
  }

  const out = new Float32Array(n * 3);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const p = y * w + x;
      const si = seg[p];
      const glow = 1.3 - 0.55 * smoothstep(0, 0.8, vor.d1[p] / cell);
      const ripple = 0.88 + 0.24 * fbm(x / (6 * s), y / (6 * s), seed + (si % 7), 3);
      // Diagonal light streaks across the window.
      const band = Math.sin((x + y * 0.6) / (55 * s) + fbm(x / (200 * s), y / (200 * s), seed, 2) * 4);
      const spec = smoothstep(0.86, 0.99, band) * 0.35;
      const leadMask = Math.max(1 - smoothstep(lead * 0.6, lead + 0.8, vor.border[p]), clamp01(splitLead[p] * 1.6));
      // Soldered came catches a thin metallic highlight along its edge.
      const sheen = leadMask > 0.5 && leadMask < 0.85 ? 0.12 : 0;
      for (let c = 0; c < 3; c++) {
        const g = glass[si * 3 + c] * glow * ripple + spec;
        out[p * 3 + c] = mix(g, leadCol[c] + sheen, leadMask);
      }
    }
  }
  // Dark vignette like the stone frame around a window.
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const dx = x / w - 0.5, dy = y / h - 0.5;
      const v = 1 - 0.55 * smoothstep(0.3, 0.75, Math.sqrt(dx * dx + dy * dy));
      const p = (y * w + x) * 3;
      out[p] *= v; out[p + 1] *= v; out[p + 2] *= v;
    }
  }
  return toImage(out, w, h);
}

function sketch(src, { seed = 1 } = {}) {
  const { width: w, height: h } = src;
  const s = scaleOf(w, h);
  const n = w * h;
  const rgb = toRGB(src);
  const lum = luminance(blur(rgb, w, h, 0.8 * s), w, h);
  // Difference of Gaussians → crisp pen contours.
  const g1 = blur(lum, w, h, 1 * s, 1);
  const g2 = blur(lum, w, h, 3 * s, 1);
  // Loose watercolor wash: heavily blurred, slightly posterized color.
  const wash = blur(rgb, w, h, 7 * s);
  const parch = hex('#eedfba');
  const inkCol = hex('#2a1c16');
  const sp = 5.5 * s + 1.5;
  const lineAt = (u, wobble) => {
    const f = (u + wobble) / sp;
    const d = Math.abs(f - Math.floor(f) - 0.5);
    return 1 - smoothstep(0.08, 0.2, d);
  };

  const out = new Float32Array(n * 3);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const p = y * w + x;
      const l = clamp01((lum[p] - 0.5) * 1.35 + 0.52);
      const wob = (fbm(x / (25 * s), y / (25 * s), seed, 2) - 0.5) * 6 * s;
      let hatch = 0;
      if (l < 0.75) hatch = Math.max(hatch, lineAt(x + y, wob) * smoothstep(0.75, 0.55, l));
      if (l < 0.5) hatch = Math.max(hatch, lineAt(x - y, wob) * smoothstep(0.5, 0.32, l));
      if (l < 0.28) hatch = Math.max(hatch, lineAt(x * 1.414, wob) * smoothstep(0.28, 0.12, l));
      const dog = g1[p] - g2[p];
      const contour = smoothstep(0.012, 0.05, -dog);
      const ink = Math.max(contour, hatch * 0.75);

      // Parchment with mottling, specks and a burnt vignette.
      const mottle = 0.88 + 0.12 * fbm(x / (70 * s), y / (70 * s), seed + 2, 4);
      const fiber = 0.97 + 0.03 * hash2(x, y, seed + 4);
      const dx = x / w - 0.5, dy = y / h - 0.5;
      const vig = 1 - 0.3 * smoothstep(0.35, 0.72, Math.sqrt(dx * dx + dy * dy));

      // Wash pigment pools in blotches rather than covering evenly.
      let [hh, ss, vv] = rgbToHsv(wash[p * 3], wash[p * 3 + 1], wash[p * 3 + 2]);
      const pig = hsvToRgb(hh, Math.min(1, ss * 1.3), Math.round(vv * 4) / 4 * 0.5 + 0.5);
      const blot = smoothstep(0.35, 0.65, fbm(x / (45 * s), y / (45 * s), seed + 6, 4));
      const alpha = 0.6 * blot * smoothstep(0.08, 0.3, ss);

      for (let c = 0; c < 3; c++) {
        const paper = parch[c] * mottle * fiber * vig;
        const washed = paper * mix(1, pig[c], alpha);
        out[p * 3 + c] = mix(washed, inkCol[c], ink);
      }
    }
  }
  return toImage(out, w, h);
}

// ---------------------------------------------------------------------------
// Catalog + prompt builder
// ---------------------------------------------------------------------------

export const FILTERS = [
  {
    id: 'udnie',
    name: 'Udnie',
    tagline: 'Swirling blue & gold modernist impasto',
    apply: udnie,
    prompt: {
      medium: 'the style of an expressive modernist oil painting',
      texture: 'heavy impasto brushstrokes and dynamic swirling curves',
      palette: 'deep cobalt and ultramarine blues with burnished gold and amber',
      background: 'thickly painted canvas',
    },
  },
  {
    id: 'wave',
    name: 'Wave',
    tagline: 'Japanese woodblock print in indigo ink',
    apply: wave,
    prompt: {
      medium: 'the style of a traditional Japanese ukiyo-e woodblock print',
      texture: 'bold black woodblock ink lines, carved wood grain and flat color fields',
      palette: 'rich indigo and Prussian blue with cream paper and touches of vermilion',
      background: 'fibrous washi parchment',
    },
  },
  {
    id: 'mosaic',
    name: 'Mosaic',
    tagline: 'Fractured tile tessellation with grout lines',
    apply: mosaic,
    prompt: {
      medium: 'the style of an abstract geometric tile mosaic',
      texture: 'fractured tessellated color tiles separated by distinct dark grout borders',
      palette: 'saturated terracotta, cobalt, ochre and emerald',
      background: 'rough stone and grout',
    },
  },
  {
    id: 'candy',
    name: 'Candy',
    tagline: 'Neon pop art with comic halftones',
    apply: candy,
    prompt: {
      medium: 'the style of a neon pop-art comic illustration',
      texture: 'fluid graphic outlines, flat cel shading and halftone comic dots',
      palette: 'hot pink, electric cyan and lemon yellow with deep violet',
      background: 'glossy printed comic paper',
    },
  },
  {
    id: 'gothic',
    name: 'Gothic',
    tagline: 'Cathedral stained glass with heavy lead',
    apply: stainedGlass,
    prompt: {
      medium: 'the style of a gothic cathedral stained glass window',
      texture: 'heavy dark leaded glass lines dividing translucent glowing segments with glass reflections',
      palette: 'jewel-toned ruby, sapphire, emerald and amber',
      background: 'backlit stained glass set in dark stone tracery',
    },
  },
  {
    id: 'tears',
    name: 'Tears',
    tagline: 'Ink cross-hatching & watercolor on parchment',
    apply: sketch,
    prompt: {
      medium: 'the style of a high-contrast pen-and-ink drawing with watercolor washes',
      texture: 'dense ink cross-hatching, confident contour lines and blooming watercolor washes',
      palette: 'sepia and iron-gall black ink with muted watercolor accents',
      background: 'aged textured parchment',
    },
  },
];

export function getFilter(id) {
  return FILTERS.find((f) => f.id === id);
}

/** Apply a filter by id. `intensity` (0..1) blends with the original. */
export function applyFilter(id, img, { intensity = 1, seed = 1 } = {}) {
  const filter = getFilter(id);
  if (!filter) throw new Error(`Unknown filter: ${id}`);
  const styled = filter.apply(img, { seed });
  if (intensity >= 1) return styled;
  const t = clamp01(intensity);
  const d = styled.data;
  for (let i = 0; i < d.length; i += 4) {
    d[i] = mix(img.data[i], d[i], t);
    d[i + 1] = mix(img.data[i + 1], d[i + 1], t);
    d[i + 2] = mix(img.data[i + 2], d[i + 2], t);
  }
  return styled;
}

/** Build a text-to-image prompt following the Prisma-style rendering template. */
export function buildPrompt(id, subject) {
  const filter = getFilter(id);
  if (!filter) throw new Error(`Unknown filter: ${id}`);
  const subj = (subject || '').trim().replace(/[.\s]+$/, '') || 'the scene';
  const { medium, texture, palette, background } = filter.prompt;
  return `A dramatic ${filter.name}-style artistic rendering of ${subj}. `
    + `Rendered in ${medium} with ${texture}. `
    + `Features a color palette dominated by ${palette}, high-contrast chiaroscuro lighting, and bold visual contours. `
    + `Textured background reminiscent of ${background}, with vibrant, expressive art stylization applied uniformly across all elements.`;
}
