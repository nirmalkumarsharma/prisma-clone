// Runs filters off the main thread so the page stays responsive while painting.
import { applyFilter } from './filters.js';

self.onmessage = ({ data }) => {
  const { id, filter, width, height, buffer, intensity, seed } = data;
  try {
    const out = applyFilter(filter, { width, height, data: new Uint8ClampedArray(buffer) }, { intensity, seed });
    self.postMessage({ id, width, height, buffer: out.data.buffer }, [out.data.buffer]);
  } catch (err) {
    self.postMessage({ id, error: err.message });
  }
};
