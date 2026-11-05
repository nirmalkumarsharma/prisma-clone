import test from 'node:test';
import assert from 'node:assert/strict';
import { FILTERS, applyFilter, buildPrompt } from '../src/filters.js';
import { sampleScene } from '../src/sample.js';
import { decodePNG, encodePNG } from '../cli/png.js';

const src = sampleScene(160, 120);

for (const f of FILTERS) {
  test(`${f.id}: same size, opaque, deterministic, and actually restyled`, () => {
    const a = applyFilter(f.id, src, { seed: 4 });
    const b = applyFilter(f.id, src, { seed: 4 });
    assert.equal(a.width, src.width);
    assert.equal(a.height, src.height);
    assert.equal(a.data.length, src.data.length);
    assert.deepEqual(a.data, b.data);
    let diff = 0;
    for (let i = 0; i < a.data.length; i += 4) {
      assert.equal(a.data[i + 3], 255);
      diff += Math.abs(a.data[i] - src.data[i]) + Math.abs(a.data[i + 1] - src.data[i + 1]) + Math.abs(a.data[i + 2] - src.data[i + 2]);
    }
    assert.ok(diff / (src.width * src.height * 3) > 10, 'output should differ strongly from the input');
  });

  test(`${f.id}: prompt follows the template`, () => {
    const p = buildPrompt(f.id, 'a lighthouse at dusk.');
    assert.match(p, new RegExp(`^A dramatic ${f.name}-style artistic rendering of a lighthouse at dusk\\. Rendered in `));
    assert.match(p, /high-contrast chiaroscuro lighting, and bold visual contours\./);
    assert.match(p, /applied uniformly across all elements\.$/);
  });
}

test('intensity 0 returns the original pixels', () => {
  const out = applyFilter('candy', src, { intensity: 0 });
  assert.deepEqual(out.data, src.data);
});

test('unknown filter throws', () => {
  assert.throws(() => applyFilter('nope', src), /Unknown filter/);
  assert.throws(() => buildPrompt('nope', 'x'), /Unknown filter/);
});

test('empty subject falls back to "the scene"', () => {
  assert.match(buildPrompt('wave', '  '), /rendering of the scene\./);
});

test('PNG round-trip', () => {
  const back = decodePNG(encodePNG(src));
  assert.equal(back.width, src.width);
  assert.deepEqual(back.data, src.data);
});
