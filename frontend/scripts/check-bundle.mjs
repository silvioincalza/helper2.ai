// Bundle budget: fails the build when the JavaScript a first visit downloads, or any page chunk, grows past its
// budget. Sizes are gzip, as the browser receives them, in KiB (1024 bytes).
// Run after `vite build`: `npm run check:bundle`.
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';

/** JavaScript a first visit to any page downloads before it can render: the entry and its preloaded imports. */
const INITIAL_JS_KIB = 160;
/** Any other chunk, loaded when its page is first opened. */
const CHUNK_KIB = 30;

const dist = new URL('../dist/', import.meta.url).pathname;
const kib = (file) => gzipSync(readFileSync(join(dist, file))).length / 1024;

const html = readFileSync(join(dist, 'index.html'), 'utf8');
const initial = [...html.matchAll(/<(?:script[^>]+src|link[^>]+rel="modulepreload"[^>]+href)="\/([^"]+\.js)"/g)].map((m) => m[1]);
if (initial.length === 0) throw new Error('No entry script found in dist/index.html');

const initialKib = initial.reduce((sum, f) => sum + kib(f), 0);
const chunks = readdirSync(join(dist, 'assets')).filter((f) => f.endsWith('.js')).map((f) => `assets/${f}`);
const lazy = chunks.filter((f) => !initial.includes(f)).map((f) => ({ f, size: kib(f) }));

const problems = [];
if (initialKib > INITIAL_JS_KIB) problems.push(`initial JS ${initialKib.toFixed(1)} KiB > ${INITIAL_JS_KIB} KiB`);
for (const { f, size } of lazy) if (size > CHUNK_KIB) problems.push(`${f} ${size.toFixed(1)} KiB > ${CHUNK_KIB} KiB`);

console.log(`initial JS: ${initialKib.toFixed(1)} KiB gzip in ${initial.length} files (budget ${INITIAL_JS_KIB} KiB)`);
console.log(`largest page chunk: ${Math.max(0, ...lazy.map((c) => c.size)).toFixed(1)} KiB gzip (budget ${CHUNK_KIB} KiB)`);
if (problems.length) {
  console.error(`Bundle budget exceeded:\n  ${problems.join('\n  ')}`);
  process.exit(1);
}
