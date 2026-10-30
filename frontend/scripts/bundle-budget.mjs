// Fails the build when the JavaScript a first visit downloads grows past the budget (ADR 0025).
// Initial JS = the entry script plus every module it preloads, as listed in dist/index.html; each is measured
// gzipped, as the browser receives it. Page chunks loaded by react-router `lazy` are not part of it.
// Run after `vite build`: node scripts/bundle-budget.mjs
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

/**
 * Gzipped kB. Measured 149.4 kB with the non-home pages lazy (150.8 kB with every page in the entry), plus ~2 %
 * headroom. Most of it is react-dom, react-router, i18next and the home page itself.
 */
const INITIAL_JS_BUDGET_KB = 152;

const dist = new URL('../dist/', import.meta.url).pathname;
const html = readFileSync(join(dist, 'index.html'), 'utf8');
const scripts = [...html.matchAll(/<(?:script[^>]*\ssrc|link[^>]*rel="modulepreload"[^>]*\shref)="\/([^"]+\.js)"/g)]
  .map((m) => m[1])
  .filter((path) => path.startsWith('assets/'));
if (scripts.length === 0) {
  console.error('bundle-budget: no entry script in dist/index.html; run vite build first');
  process.exit(1);
}
const kb = (bytes) => bytes / 1000;
const sizes = scripts.map((path) => ({ path, gzip: kb(gzipSync(readFileSync(join(dist, path))).length) }));
const total = sizes.reduce((sum, s) => sum + s.gzip, 0);
for (const s of sizes) console.log(`${s.gzip.toFixed(2).padStart(8)} kB  ${s.path}`);
console.log(`${total.toFixed(2).padStart(8)} kB  initial JS, gzipped (budget ${INITIAL_JS_BUDGET_KB} kB)`);
if (total > INITIAL_JS_BUDGET_KB) {
  console.error(`bundle-budget: initial JS is ${total.toFixed(2)} kB gzipped, over the ${INITIAL_JS_BUDGET_KB} kB budget`);
  process.exit(1);
}
