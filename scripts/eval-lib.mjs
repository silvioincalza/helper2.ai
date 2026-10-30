// Shared by scripts/eval-guide.mjs and scripts/eval-setup.mjs: session token, resource identity, judgements, metrics.
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const esc = (s) => String(s ?? '').replaceAll('|', '\\|').replaceAll('\n', ' ');
export const fmt = (x) => (x == null || Number.isNaN(x) ? '–' : x.toFixed(3));

/** A CSRF token from GET /api/v1/me; the POST endpoints need it as header and cookie. */
export async function csrfToken(base) {
  const res = await fetch(`${base}/api/v1/me`);
  const cookies = res.headers.getSetCookie?.() ?? [];
  const match = cookies.map((c) => /^XSRF-TOKEN=([^;]*)/.exec(c)).find(Boolean);
  if (!match) throw new Error('No XSRF-TOKEN cookie from /api/v1/me; is the backend running?');
  return match[1];
}

export async function postJson(base, token, path, body) {
  return fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-xsrf-token': token, cookie: `XSRF-TOKEN=${token}` },
    body: JSON.stringify(body),
  });
}

const TRACKING = new Set(['ref', 'ref_src', 'fbclid', 'gclid', 'mc_cid', 'mc_eid', 'source']);

/** Same rules as the backend's CanonicalUrl: identity of a resource URL. Null when it is not an http(s) URL. */
export function canonicalUrl(raw) {
  if (!raw || raw.length > 2000) return null;
  let url;
  try {
    url = new URL(raw.trim().replace(/^(git\+)?(git|ssh|https?):\/\/(git@)?/, 'https://'));
  } catch {
    return null;
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
  const rawHost = url.hostname.toLowerCase();
  if (!rawHost.includes('.')) return null;
  const host = rawHost.replace(/^www\./, '');
  const port = url.port && url.port !== '80' && url.port !== '443' ? `:${url.port}` : '';
  const segments = url.pathname.split('/').filter(Boolean);
  if (host === 'github.com' && segments.length >= 2) {
    return `https://github.com/${segments[0].toLowerCase()}/${segments[1].toLowerCase().replace(/\.git$/, '')}`;
  }
  const query = url.search.slice(1).split('&').filter((p) => p.trim())
    .filter((p) => { const n = p.split('=')[0].toLowerCase(); return !n.startsWith('utm_') && !TRACKING.has(n); })
    .sort().join('&');
  return `https://${host}${port}${url.pathname.replace(/\/+$/, '')}${query ? `?${query}` : ''}`;
}

const NAMESPACE = Buffer.from('6f1c2a8e4b1d5c3e9a7f2d4e6b8c0a12', 'hex');

/** The backend's ResourceId: UUIDv5 of the canonical URL in the Helper2 namespace. */
export function resourceId(canonical) {
  const h = createHash('sha1').update(NAMESPACE).update(canonical, 'utf8').digest().subarray(0, 16);
  h[6] = (h[6] & 0x0f) | 0x50;
  h[8] = (h[8] & 0x3f) | 0x80;
  const x = h.toString('hex');
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`;
}

/**
 * Graded judgements (query_id<TAB>url<TAB>grade, grade 0..3, `#` comments) as query id → resource id → grade.
 * Null when the file does not exist.
 */
export async function readQrels(fileUrl) {
  let text;
  try {
    text = await readFile(fileUrl, 'utf8');
  } catch {
    return null;
  }
  const rows = text.split('\n').map((l) => l.trimEnd()).filter((l) => l && !l.startsWith('#'));
  if (rows[0] !== 'query_id\turl\tgrade') throw new Error(`${fileUrl.pathname}: first line must be query_id<TAB>url<TAB>grade`);
  const qrels = new Map();
  rows.slice(1).forEach((row, i) => {
    const [q, url, g] = row.split('\t');
    const canonical = canonicalUrl(url);
    const grade = Number(g);
    if (!canonical || !Number.isInteger(grade) || grade < 0 || grade > 3) throw new Error(`${fileUrl.pathname} line ${i + 2}: bad row`);
    if (!qrels.has(q)) qrels.set(q, new Map());
    qrels.get(q).set(resourceId(canonical), grade);
  });
  return qrels;
}

const RELEVANT = 2;
const dcg = (grades, k) => grades.slice(0, k).reduce((s, g, i) => s + (2 ** (g ?? 0) - 1) / Math.log2(i + 2), 0);

/** Share of the first k positions holding a relevant (grade >= 2) result; an empty position counts as not relevant. */
export const precisionAt = (grades, k) => grades.slice(0, k).filter((g) => (g ?? 0) >= RELEVANT).length / k;

/** nDCG@k against the ideal order of every judged grade of the query; null when none is above 0. */
export function ndcgAt(grades, judged, k) {
  const ideal = dcg([...judged].sort((a, b) => b - a), k);
  return ideal === 0 ? null : dcg(grades, k) / ideal;
}

export const mean = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null);
