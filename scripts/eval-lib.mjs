import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const esc = (s) => String(s ?? '').replaceAll('|', '\\|').replaceAll('\n', ' ');
export const fmt = (x) => (x == null || Number.isNaN(x) ? '–' : x.toFixed(3));

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

export const EXIT_FAILED = 1;
export const EXIT_BUDGET_SPENT = 3;
export const EXIT_NETWORK_QUOTA = 4;
export const MODEL_FREE_ANSWERS_IN_A_ROW = 3;

export const noticeCodes = (answer) => (answer?.notices ?? []).map((n) => n.code);

export function guideRunStop({ id, answer, withoutModel, base, report }) {
  const notice = (code) => (answer?.notices ?? []).find((n) => n.code === code);
  const budget = notice('GUIDE_BUDGET_SPENT');
  if (budget) {
    return {
      exitCode: EXIT_BUDGET_SPENT,
      message:
        `Stopped at ${id}: the guide's daily model budget on ${base} is used up (GUIDE_BUDGET_SPENT), so answers are ` +
        `search only and the run would not measure the model. The backend says: "${budget.message}" ${report} is ` +
        `unchanged. Run again ${budget.retryAt ? `after ${budget.retryAt}` : 'when the budget opens'} (the next part ` +
        'of the UTC day, H2_GUIDE_BUDGET_PACE_SLOTS, or 00:00 UTC), or against a backend with a higher ' +
        'H2_GUIDE_DAILY_MODEL_CALLS.',
    };
  }
  const network = notice('GUIDE_NETWORK_QUOTA');
  if (network) {
    return {
      exitCode: EXIT_NETWORK_QUOTA,
      message:
        `Stopped at ${id}: the runner's network has used its guide questions for today on ${base} ` +
        `(GUIDE_NETWORK_QUOTA), so answers are search only and the run would not measure the model. ${report} is ` +
        `unchanged. Run again after ${network.retryAt ?? '00:00 UTC'}, from another network, or against a backend ` +
        'with a higher H2_GUIDE_NETWORK_DAILY_QUESTIONS.',
    };
  }
  const quota = notice('GUIDE_DAILY_QUOTA');
  if (quota) {
    return {
      exitCode: EXIT_FAILED,
      message:
        `Stopped at ${id}: this address has used its daily guide quota (GUIDE_DAILY_QUOTA), so answers are search ` +
        `only and the run would not measure the model. ${report} is unchanged. Run again after ${quota.retryAt ?? '00:00 UTC'}, ` +
        'from another address, or against a backend with a higher H2_GUIDE_CLIENT_DAILY_QUESTIONS.',
    };
  }
  if (withoutModel >= MODEL_FREE_ANSWERS_IN_A_ROW) {
    return {
      exitCode: EXIT_FAILED,
      message:
        `Stopped at ${id}: ${MODEL_FREE_ANSWERS_IN_A_ROW} answers in a row without a model (LLM_UNAVAILABLE) and no ` +
        `budget or quota notice: the model provider is down, too slow or not configured on ${base}. ${report} is unchanged.`,
    };
  }
  return null;
}

export function lastGuideEvent(text) {
  let last = null;
  for (const block of text.split(/\n\n+/)) {
    const event = /^event:(.*)$/m.exec(block)?.[1]?.trim();
    const data = /^data:(.*)$/m.exec(block)?.[1];
    if ((event === 'answer' || event === 'error') && data) last = { event, data: JSON.parse(data) };
  }
  return last;
}

export const guideRequest = (q) => (q.path ? { request: q.request, path: q.path } : { request: q.request });

const TRACKING = new Set(['ref', 'ref_src', 'fbclid', 'gclid', 'mc_cid', 'mc_eid', 'source']);
const ANY_REF = 'HEAD';
const GITHUB_SITE_PAGES = new Set(['about', 'apps', 'collections', 'customer-stories', 'enterprise', 'events', 'explore', 'features', 'login',
  'marketplace', 'orgs', 'pricing', 'readme', 'resources', 'security', 'settings', 'solutions', 'sponsors', 'team', 'topics', 'trending']);
const ARXIV_VIEWS = new Set(['abs', 'pdf', 'html']);
const ARXIV_VERSION = /v\d+$/;
const ARXIV_ID = /^\d{4}\.\d{4,5}(v\d+)?$/;
const HF_REPOSITORY_KINDS = new Set(['datasets', 'spaces']);
const HF_SITE_PAGES = new Set(['api', 'blog', 'changelog', 'chat', 'collections', 'course', 'docs', 'enterprise', 'hardware',
  'inference-endpoints', 'join', 'learn', 'login', 'models', 'new', 'notifications', 'organizations', 'papers', 'posts', 'pricing',
  'privacy', 'pro', 'search', 'settings', 'tasks', 'terms-of-service']);

const isReadme = (name) => name.split('.')[0].toLowerCase() === 'readme';
const isDotSegment = (segment) => ['.', '..'].includes(segment.replace(/%2e/gi, '.'));

function githubIdentity(segments) {
  if (segments.length < 2 || GITHUB_SITE_PAGES.has(segments[0].toLowerCase())) return null;
  const repository = `https://github.com/${segments[0].toLowerCase()}/${segments[1].toLowerCase().replace(/\.git$/, '')}`;
  const [kind, ref] = segments.slice(2);
  const inside = segments.slice(4);
  if (ref === undefined || !inside.length) return repository;
  if (kind === 'tree') return `${repository}/tree/${ANY_REF}/${inside.join('/')}`;
  if (kind === 'blob' && inside.length > 1 && isReadme(inside.at(-1))) return `${repository}/tree/${ANY_REF}/${inside.slice(0, -1).join('/')}`;
  return repository;
}

function arxivIdentity(segments) {
  if (segments.length < 2 || !ARXIV_VIEWS.has(segments[0])) return null;
  return `https://arxiv.org/abs/${segments.slice(1).join('/').replace(/\.pdf$/, '').replace(ARXIV_VERSION, '')}`;
}

function huggingFaceIdentity(segments) {
  if (!segments.length) return null;
  if (segments[0] === 'papers' && segments.length >= 2 && ARXIV_ID.test(segments[1])) {
    return `https://arxiv.org/abs/${segments[1].replace(ARXIV_VERSION, '')}`;
  }
  const repositoryLength = HF_REPOSITORY_KINDS.has(segments[0]) ? 3 : 2;
  if (segments.length < repositoryLength || HF_SITE_PAGES.has(segments[0])) return null;
  return `https://huggingface.co/${segments.slice(0, repositoryLength).join('/')}`;
}

const HOST_RULES = new Map([
  ['github.com', githubIdentity],
  ['arxiv.org', arxivIdentity],
  ['export.arxiv.org', arxivIdentity],
  ['huggingface.co', huggingFaceIdentity],
  ['hf.co', huggingFaceIdentity],
]);

export function canonicalUrl(raw) {
  if (!raw?.trim() || raw.length > 2000) return null;
  const cleaned = raw.trim().replace(/^(git\+)?(git|ssh|https?):\/\/(git@)?/, 'https://');
  let url;
  try {
    url = new URL(cleaned);
  } catch {
    return null;
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
  const rawHost = url.hostname.toLowerCase();
  if (!rawHost.includes('.')) return null;
  const host = rawHost.replace(/^www\./, '');
  const port = url.port && url.port !== '80' && url.port !== '443' ? `:${url.port}` : '';
  const rawPath = /^https?:\/\/[^/?#]*([^?#]*)/i.exec(cleaned)?.[1] ?? '';
  const segments = rawPath.split('/').filter(Boolean);
  if (segments.some(isDotSegment)) return null;
  const identity = HOST_RULES.get(host)?.(segments);
  if (identity) return identity;
  const query = url.search.slice(1).split('&').filter((p) => p.trim())
    .filter((p) => { const n = p.split('=')[0].toLowerCase(); return !n.startsWith('utm_') && !TRACKING.has(n); })
    .sort().join('&');
  return `https://${host}${port}${rawPath.replace(/\/+$/, '')}${query ? `?${query}` : ''}`;
}

const NAMESPACE = Buffer.from('6f1c2a8e4b1d5c3e9a7f2d4e6b8c0a12', 'hex');

export function resourceId(canonical) {
  const h = createHash('sha1').update(NAMESPACE).update(canonical, 'utf8').digest().subarray(0, 16);
  h[6] = (h[6] & 0x0f) | 0x50;
  h[8] = (h[8] & 0x3f) | 0x80;
  const x = h.toString('hex');
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`;
}

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

export const precisionAt = (grades, k) => grades.slice(0, k).filter((g) => (g ?? 0) >= RELEVANT).length / k;

export function ndcgAt(grades, judged, k) {
  const ideal = dcg([...judged].sort((a, b) => b - a), k);
  return ideal === 0 ? null : dcg(grades, k) / ideal;
}

export const mean = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null);
