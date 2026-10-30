// Bundle budget: fails the build when the JavaScript a first visit downloads, or any page chunk, grows past its
// budget. Sizes are gzip, as the browser receives them. Run after `vite build`: `npm run check:bundle`.
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

/** JavaScript a first visit to any page downloads before it can render: the entry and its preloaded imports. */
const INITIAL_JS_KB = 160;
/** Any other chunk, loaded when its page is first opened. */
const CHUNK_KB = 30;

const dist = new URL('../dist/', import.meta.url).pathname;
const kb = (file) => gzipSync(readFileSync(join(dist, file))).length / 1024;

const html = readFileSync(join(dist, 'index.html'), 'utf8');
const initial = [...html.matchAll(/<(?:script[^>]+src|link[^>]+rel="modulepreload"[^>]+href)="\/([^"]+\.js)"/g)].map((m) => m[1]);
if (initial.length === 0) throw new Error('No entry script found in dist/index.html');

const initialKb = initial.reduce((sum, f) => sum + kb(f), 0);
const chunks = readdirSync(join(dist, 'assets')).filter((f) => f.endsWith('.js')).map((f) => `assets/${f}`);
const lazy = chunks.filter((f) => !initial.includes(f)).map((f) => ({ f, size: kb(f) }));

const problems = [];
if (initialKb > INITIAL_JS_KB) problems.push(`initial JS ${initialKb.toFixed(1)} kB > ${INITIAL_JS_KB} kB`);
for (const { f, size } of lazy) if (size > CHUNK_KB) problems.push(`${f} ${size.toFixed(1)} kB > ${CHUNK_KB} kB`);

console.log(`initial JS: ${initialKb.toFixed(1)} kB gzip in ${initial.length} files (budget ${INITIAL_JS_KB} kB)`);
console.log(`largest page chunk: ${Math.max(0, ...lazy.map((c) => c.size)).toFixed(1)} kB gzip (budget ${CHUNK_KB} kB)`);
if (problems.length) {
  console.error(`Bundle budget exceeded:\n  ${problems.join('\n  ')}`);
  process.exit(1);
}
