// Procedural demo scene (sunset over mountains and sea, with a boat) as ImageData-like RGBA.
// Pure JS so it works in the browser, in tests and in the CLI.

export function sampleScene(width = 640, height = 480) {
  const data = new Uint8ClampedArray(width * height * 4);
  const sunX = width * 0.68, sunY = height * 0.42, sunR = height * 0.11;
  const horizon = height * 0.58;
  const ridge = (x, f, a, o) => o + a * (Math.sin(x * f) * 0.6 + Math.sin(x * f * 2.3 + 1.7) * 0.3 + Math.sin(x * f * 5.1) * 0.1);
  const lerp = (a, b, t) => a + (b - a) * t;

  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      let r, g, b;
      const t = y / horizon;
      // Sky: violet → orange → yellow toward the horizon.
      r = lerp(70, 250, t); g = lerp(40, 170, t * t); b = lerp(140, 90, t);
      const ds = Math.hypot(x - sunX, y - sunY);
      if (ds < sunR) { r = 255; g = 230; b = 150; } else if (ds < sunR * 2.2) {
        const k = 1 - (ds - sunR) / (sunR * 1.2);
        r = lerp(r, 255, k * 0.6); g = lerp(g, 200, k * 0.6); b = lerp(b, 120, k * 0.4);
      }
      const far = ridge(x, 0.012, height * 0.07, height * 0.45);
      const near = ridge(x + 300, 0.008, height * 0.09, height * 0.53);
      if (y > far) { r = 95; g = 70; b = 120; }
      if (y > near) { r = 45; g = 50; b = 85; }
      if (y > horizon) {
        const d = (y - horizon) / (height - horizon);
        r = lerp(40, 10, d); g = lerp(80, 40, d); b = lerp(140, 90, d);
        const wave = Math.sin(x * 0.05 + y * 0.35) * Math.sin(y * 0.9);
        if (wave > 0.75) { r += 60; g += 70; b += 80; }
        // Sun reflection column.
        if (Math.abs(x - sunX) < sunR * (1 - d * 0.5) && Math.sin(y * 1.3 + x * 0.1) > 0.2) {
          r = 250; g = 190; b = 110;
        }
      }
      // Boat: hull + sail.
      const bx = width * 0.3, by = horizon + height * 0.12, bw = width * 0.12;
      if (y > by && y < by + height * 0.03 && Math.abs(x - bx) < bw * (1 - (y - by) / (height * 0.06))) {
        r = 120; g = 40; b = 30;
      }
      if (y < by && y > by - height * 0.16 && x > bx - 2 && x < bx + (by - y) * -0.45 + bw * 0.6) {
        r = 240; g = 235; b = 220;
      }
      const i = (y * width + x) * 4;
      data[i] = r; data[i + 1] = g; data[i + 2] = b; data[i + 3] = 255;
    }
  }
  return { width, height, data };
}
