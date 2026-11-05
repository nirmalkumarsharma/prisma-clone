// Promise wrapper around the filter worker, with cancellation and a main-thread fallback
// for browsers without module-worker support.
import { applyFilter } from './filters.js';

export class Cancelled extends Error {
  constructor() { super('cancelled'); this.name = 'Cancelled'; }
}

export class FilterRunner {
  constructor() {
    this.seq = 0;
    this.pending = new Map(); // id → { resolve, reject, args }
    this.fallback = typeof Worker === 'undefined';
    if (!this.fallback) this.spawn();
  }

  spawn() {
    try {
      this.worker = new Worker(new URL('./worker.js', import.meta.url), { type: 'module' });
    } catch {
      this.useFallback();
      return;
    }
    this.worker.onmessage = ({ data }) => {
      const job = this.pending.get(data.id);
      if (!job) return;
      this.pending.delete(data.id);
      if (data.error) job.reject(new Error(data.error));
      else job.resolve({ width: data.width, height: data.height, data: new Uint8ClampedArray(data.buffer) });
    };
    this.worker.onerror = (e) => {
      e.preventDefault();
      this.useFallback();
    };
  }

  // Worker failed to load: finish every queued job on the main thread instead.
  useFallback() {
    this.fallback = true;
    this.worker?.terminate();
    this.worker = null;
    const jobs = [...this.pending.values()];
    this.pending.clear();
    for (const { resolve, reject, args } of jobs) {
      try { resolve(applyFilter(...args)); } catch (err) { reject(err); }
    }
  }

  run(filter, img, opts) {
    const args = [filter, img, opts];
    if (this.fallback) {
      // Yield a frame first so a "busy" indicator can paint.
      return new Promise((r) => setTimeout(r, 16)).then(() => applyFilter(...args));
    }
    const id = ++this.seq;
    const copy = img.data.slice();
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject, args });
      this.worker.postMessage(
        { id, filter, width: img.width, height: img.height, buffer: copy.buffer, intensity: opts.intensity, seed: opts.seed },
        [copy.buffer],
      );
    });
  }

  // Abandon in-flight work (e.g. the user picked another filter mid-render).
  cancel() {
    if (this.fallback || this.pending.size === 0) return;
    this.worker.terminate();
    for (const { reject } of this.pending.values()) reject(new Cancelled());
    this.pending.clear();
    this.spawn();
  }
}
