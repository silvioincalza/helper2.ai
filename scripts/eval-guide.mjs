#!/usr/bin/env node
// Runs a fixed guide question set against a running backend and writes a report.
// Usage: node scripts/eval-guide.mjs [baseUrl] [--core] [--repeat <n>] [--min-pass <share>] | [--adversarial | --intents]
// (default http://localhost:8080). Needs Node 18+ (global fetch).
//
// Cache: every run must measure real model calls, so the backend under test runs with
// H2_GUIDE_CACHE_TTL=PT1S (staging): an answer expires before the next question is sent, and a rerun of the
// same set is never served from the cache. The questions are sent exactly as written, never with a suffix: a suffix
// changes the question (the 29 Oct 2026 run appended " p19" and the planner read it as a constraint). An answer that
// still comes from the cache (generatedAt older than the request) is marked "(cache)", left out of the latency figures
// and counted in the summary.
//
// Pace: 7 s between questions, below the guide's 10-per-minute limit, and never more than 25 live GitHub searches
// in any 60 s (GitHub allows 30 a minute per token). The live GitHub calls are counted from the source list of
// every answer and every search the script sends (servedFrom LIVE); before a request the script waits until the
// last 60 s leave room for the most a request has used so far.
//
// Sources: each answer's source list (outcome, served from, result count) is printed per question, and every source
// that did not succeed is listed in the report. A source RATE_LIMITED or an HTTP 429 counts as a 429.
//
// Daily quota and budget: every question counts against the per-client daily quota (H2_GUIDE_CLIENT_DAILY_QUESTIONS)
// and spends about 3 of the guide's daily model calls (H2_GUIDE_DAILY_MODEL_CALLS). The script stops with an error,
// and leaves the report unchanged, as soon as an answer carries the GUIDE_DAILY_QUOTA notice, or after 3 answers in a
// row without a model (LLM_UNAVAILABLE: the model budget is spent or the provider is down).
//
// Set: docs/eval/guide-queries.json. --core runs only the 20 questions without "extended": true (the nightly run on
// staging, .github/workflows/guide-eval.yml, ADR 0030); the default runs all of them.
//
// Answered: a question is answered when a model answered it fresh (HTTP 200, not from the cache, not
// LLM_UNAVAILABLE) with at least one AI pick, a comparison, an explanation, or a dated record (WHEN). A question with
// "expectNoPick": true (and a "why") passes when it stays empty; any other passes when it is answered. The answer rate
// counts only the questions that expect an answer. --min-pass 0.5 exits with code 1 when fewer than half pass (over
// all runs); the report is written either way.
//
// Grading pool: for every question with no answer, the script sends the answer's own search terms (searchQuery) to
// GET /api/v1/search with size 24, right after the answer, and writes those results to the pool as "candidate"
// rows. It is close to the guide's 24 candidates, not the same list: the guide's intent filters, sort and web query
// are not sent, and a source may answer from its cache. The pool (backend/eval-data/guide-pool.tsv, git-ignored) also
// holds the AI picks no one has graded. Its first three columns are those of docs/eval/guide-qrels.tsv.
//
// --repeat <n> runs the set n times (default 1) to see how often answers flip: a question flips when it is answered
// in one run and not in another, and its top pick flips when the first AI pick differs between runs that have one.
// The per-query table shows the first run. Stored web results (Tavily, 24-48 h) are the same in every run, so repeats
// look more stable than a day apart.
//
// Hand-written notes: the text between the HAND_WRITTEN_START and HAND_WRITTEN_END markers in
// docs/evaluation.md is kept as it is; the rest of the file is regenerated. Update those notes after each run.
//
// --adversarial runs docs/eval/guide-adversarial.json instead: hostile questions, each with expectations the
// answer must meet (no prompt, no given text in reasons or prompt, ...). It writes docs/evaluation-adversarial.md
// and exits with code 1 when a case fails.
//
// --intents runs docs/eval/guide-intents.json: at least two questions per intent and seven that must or must not be asked back
// (ADR 0032 §10), each with the pass rule of its intent. It writes docs/evaluation-intents.md and exits with code 1
// when a case fails.
//
// Relevance: when docs/eval/guide-qrels.tsv holds judgements (query_id, url, grade 0-3), the default and --intents
// reports add P@3 and nDCG@3 of the AI picks, and the default report the share of graded candidates that are
// relevant for the questions with no answer.
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { canonicalUrl, csrfToken, esc, fmt, lastGuideEvent, mean, ndcgAt, postJson, precisionAt, readQrels, resourceId, sleep } from './eval-lib.mjs';

const args = process.argv.slice(2);
const adversarial = args.includes('--adversarial');
const intents = args.includes('--intents');
const core = args.includes('--core');
const rescore = args.includes('--rescore');
const valueOf = (flag) => {
  const at = args.indexOf(flag);
  return at >= 0 ? args[at + 1] : undefined;
};
const minPass = valueOf('--min-pass') != null ? Number(valueOf('--min-pass')) : null;
if (minPass != null && !(minPass >= 0 && minPass <= 1)) throw new Error('--min-pass takes a share between 0 and 1, for example 0.5');
let repeat = valueOf('--repeat') != null ? Number(valueOf('--repeat')) : 1;
if (!(Number.isInteger(repeat) && repeat >= 1 && repeat <= 5)) throw new Error('--repeat takes a whole number of runs from 1 to 5');
if ((repeat > 1 || rescore) && (adversarial || intents)) throw new Error('--repeat and --rescore work on the default set only');
// The values after --min-pass and --repeat are not the base URL; any other bare argument may be.
const valueAt = new Set(['--min-pass', '--repeat'].map((f) => args.indexOf(f)).filter((i) => i >= 0).map((i) => i + 1));
const base = (args.find((a, i) => !a.startsWith('--') && !valueAt.has(i)) ?? 'http://localhost:8080').replace(/\/$/, '');
const QUERY_FILE = adversarial ? 'guide-adversarial.json' : intents ? 'guide-intents.json' : 'guide-queries.json';
const allQueries = JSON.parse(await readFile(new URL(`../docs/eval/${QUERY_FILE}`, import.meta.url), 'utf8'));
let queries = core ? allQueries.filter((q) => !q.extended) : allQueries;
const qrels = await readQrels(new URL('../docs/eval/guide-qrels.tsv', import.meta.url));
const PAUSE_MS = Number(process.env.H2_EVAL_PAUSE_MS ?? 7000);
const GITHUB_PER_MINUTE = 25;
const POOL_SIZE = 24;
const OUTPUT = new URL(
  adversarial ? '../docs/evaluation-adversarial.md' : intents ? '../docs/evaluation-intents.md' : '../docs/evaluation.md',
  import.meta.url,
);
const POOL = new URL('../backend/eval-data/guide-pool.tsv', import.meta.url);
const RAW = new URL('../backend/eval-data/guide-runs.json', import.meta.url);
const HAND_WRITTEN_START = '<!-- hand-written: kept by scripts/eval-guide.mjs -->';
const HAND_WRITTEN_END = '<!-- end hand-written -->';
const REPORT = adversarial ? 'docs/evaluation-adversarial.md' : intents ? 'docs/evaluation-intents.md' : 'docs/evaluation.md';

/** Live GitHub searches in the last minute, so the run stays below GitHub's 30 a minute per token. */
const github = {
  calls: [],
  most: 1,
  record(sources) {
    const n = (sources ?? []).filter((s) => s.sourceId === 'github' && s.servedFrom === 'LIVE').length;
    const now = Date.now();
    for (let i = 0; i < n; i++) this.calls.push(now);
    this.most = Math.max(this.most, n);
  },
  async room() {
    for (;;) {
      const now = Date.now();
      this.calls = this.calls.filter((t) => t > now - 60_000);
      if (this.calls.length + this.most <= GITHUB_PER_MINUTE) return;
      const wait = this.calls[0] + 60_000 - now + 100;
      console.log(`  pacing: ${this.calls.length} live GitHub searches in the last minute, waiting ${Math.ceil(wait / 1000)} s`);
      await sleep(wait);
    }
  },
};

/** Sources that did not succeed, and the 429s among them. */
const sourceProblems = (sources) => (sources ?? []).filter((s) => s.outcome !== 'SUCCEEDED');
const rateLimited = (sources) => (sources ?? []).filter((s) => s.outcome === 'RATE_LIMITED').length;
const sourceLine = (sources) => (sources ?? [])
  .map((s) => `${s.sourceId} ${s.outcome}${s.servedFrom && s.servedFrom !== 'LIVE' ? `/${s.servedFrom}` : ''} ${s.resultCount ?? 0}`)
  .join(', ') || 'no sources';

async function ask(token, q) {
  await github.room();
  const startedAt = Date.now();
  const started = performance.now();
  // The UI sends no audience path; `path` in the query file is only a group label.
  // The stream is the only guide endpoint: the answer is its last event, so the time is to the whole answer.
  const res = await postJson(base, token, '/api/v1/guide/stream', { request: q.request });
  const text = res.ok ? await res.text() : '';
  const ms = Math.round(performance.now() - started);
  if (!res.ok) return { q, ms, error: `HTTP ${res.status}`, http429: res.status === 429 };
  const last = lastGuideEvent(text);
  if (!last) return { q, ms, error: 'stream ended without an answer' };
  if (last.event === 'error') return { q, ms, error: `HTTP ${last.data.status ?? 500}`, http429: last.data.status === 429 };
  const a = last.data;
  github.record(a.sources);
  // A cached answer keeps its original generatedAt, so an answer older than the request came from the cache.
  const cached = Date.parse(a.generatedAt) < startedAt - 1000;
  return { q, ms, a, cached };
}

/**
 * The search the guide ran for [answer], sent again to GET /api/v1/search: close to the guide's candidates (see the
 * header), for a person to grade. Null when the search failed.
 */
async function candidates(answer) {
  if (!answer.searchQuery) return null;
  await github.room();
  const url = `${base}/api/v1/search?q=${encodeURIComponent(answer.searchQuery.slice(0, 200))}&size=${POOL_SIZE}`;
  const res = await fetch(url);
  if (!res.ok) return { error: `HTTP ${res.status}`, http429: res.status === 429, results: [], sources: [] };
  const body = await res.json();
  github.record(body.sources);
  return { results: body.results ?? [], sources: body.sources ?? [] };
}

const aiPicks = (a) => a.recommendations.filter((x) => x.aiReasoned).length;
/** The model gave an answer of the question's kind: picks, a comparison, an explanation, or a dated record (WHEN). */
const answered = (a) => aiPicks(a) > 0 || Boolean(a.comparison) || (a.explanation?.sentences?.length ?? 0) > 0
  || (a.intent === 'WHEN' && a.status === 'OK' && a.recommendations.length > 0);
/** Answered fresh by a model, as the question expects (empty for "expectNoPick"). */
const passes = (r) => Boolean(r.a) && !r.cached && r.a.status !== 'LLM_UNAVAILABLE' && answered(r.a) !== Boolean(r.q.expectNoPick);

/** Grades written into the pool itself are never overwritten: they must reach docs/eval/guide-qrels.tsv first. */
async function stopOnGradedPool() {
  try {
    const graded = (await readFile(POOL, 'utf8')).split('\n').slice(1).filter((l) => l.split('\t')[2]?.trim());
    if (graded.length) {
      console.error(`backend/eval-data/guide-pool.tsv has ${graded.length} graded rows: copy their first three columns ` +
        'to docs/eval/guide-qrels.tsv (or clear the grade column), then run again. Nothing was sent.');
      process.exit(1);
    }
  } catch (e) {
    if (e.code !== 'ENOENT') throw e;
  }
}
if (!adversarial) await stopOnGradedPool();

const runs = [];
let runAt = new Date().toISOString();
let runBase = base;
let withoutModel = 0;
const token = rescore ? null : await csrfToken(base);
for (let run = 1; run <= (rescore ? 0 : repeat); run++) {
  if (repeat > 1) console.log(`run ${run} of ${repeat}`);
  const rows = [];
  for (const [i, q] of queries.entries()) {
    if (i > 0 || run > 1) await sleep(PAUSE_MS);
    const r = await ask(token, q);
    if (r.a && !r.cached && !answered(r.a) && !q.expectNoPick && !adversarial && !intents) r.pool = await candidates(r.a);
    rows.push(r);
    console.log(`${q.id} ${r.error ?? r.a.status} ${r.ms}ms${r.a ? ` | ${sourceLine(r.a.sources)}` : ''}${r.pool ? ` | pool ${r.pool.error ?? r.pool.results.length}` : ''}`);
    if (r.a?.notices?.some((n) => n.code === 'GUIDE_DAILY_QUOTA')) {
      console.error(
        `Stopped at ${q.id}: this address has used its daily guide quota (GUIDE_DAILY_QUOTA), so answers are search ` +
          `only and the run would not measure the model. ${REPORT} is unchanged. Run again after 00:00 UTC, ` +
          'from another address, or against a backend with a higher H2_GUIDE_CLIENT_DAILY_QUESTIONS.',
      );
      process.exit(1);
    }
    withoutModel = r.a?.status === 'LLM_UNAVAILABLE' ? withoutModel + 1 : 0;
    if (withoutModel >= 3) {
      console.error(
        `Stopped at ${q.id}: 3 answers in a row without a model (LLM_UNAVAILABLE). The guide's daily model calls ` +
          `(H2_GUIDE_DAILY_MODEL_CALLS) are spent or the provider is down. ${REPORT} is unchanged.`,
      );
      process.exit(1);
    }
  }
  runs.push(rows);
}
if (rescore) {
  // Grades are added after a run: rebuild the report from the answers of the last run, with no new request.
  const saved = JSON.parse(await readFile(RAW, 'utf8'));
  const savedIds = new Set(saved.runs[0].map((r) => r.id));
  queries = allQueries.filter((q) => savedIds.has(q.id));
  repeat = saved.runs.length;
  const byId = new Map(queries.map((q) => [q.id, q]));
  for (const rs of saved.runs) runs.push(rs.filter((r) => byId.has(r.id)).map(({ id, ...r }) => ({ ...r, q: byId.get(id) })));
  runAt = saved.at;
  runBase = saved.base;
  console.log(`rescoring ${runs.length} saved run(s) of ${saved.at} against ${saved.base}`);
} else if (!adversarial && !intents) {
  await mkdir(new URL('.', RAW), { recursive: true });
  await writeFile(RAW, JSON.stringify({ at: runAt, base, repeat, core, runs: runs.map((rs) => rs.map(({ q, ...r }) => ({ id: q.id, ...r }))) }));
  console.log('wrote backend/eval-data/guide-runs.json: the raw answers, for --rescore after grading');
}
const rows = runs[0];

/** Grades of the AI picks of [answer] in order, null where no judge has graded the pick. */
function pickGrades(id, answer) {
  const judged = qrels?.get(id) ?? new Map();
  return answer.recommendations.filter((x) => x.aiReasoned).map((x) => judged.get(x.resourceId) ?? null);
}

const poolLine = (cells) => cells.map((c) => String(c ?? '').replaceAll('\t', ' ').replaceAll('\n', ' ')).join('\t');

/** P@3 and nDCG@3 of the AI picks over the judged queries, and the picks still to grade. */
function relevance(answeredRows) {
  const judgedRows = answeredRows.filter((r) => [...(qrels?.get(r.q.id)?.values() ?? [])].some((g) => g > 0));
  const p3 = mean(judgedRows.map((r) => precisionAt(pickGrades(r.q.id, r.a), 3)));
  const n3 = mean(judgedRows.map((r) => ndcgAt(pickGrades(r.q.id, r.a), [...qrels.get(r.q.id).values()], 3)).filter((x) => x != null));
  const toGrade = answeredRows.flatMap((r) => r.a.recommendations.filter((x) => x.aiReasoned)
    .filter((x) => !qrels?.get(r.q.id)?.has(x.resourceId))
    .map((x) => ({ q: r.q.id, id: x.resourceId, line: poolLine([r.q.id, canonicalUrl(x.url) ?? x.url, '', r.q.request, x.title, x.type, 'pick']) })));
  return { judged: judgedRows.length, p3, n3, toGrade };
}

/**
 * The candidates of the questions with no answer: the ones still to grade, and over the graded ones the share
 * relevant (grade 2 or 3) and the questions with at least one relevant candidate.
 */
function candidateRelevance(allRows) {
  const toGrade = [];
  const grades = [];
  const recoverable = new Set();
  const asked = new Set();
  for (const r of allRows.filter((x) => x.pool && !x.q.expectNoPick)) {
    asked.add(r.q.id);
    r.pool.results.forEach((c, i) => {
      const canonical = canonicalUrl(c.url) ?? c.url;
      const id = c.id ?? resourceId(canonical);
      const grade = qrels?.get(r.q.id)?.get(id);
      if (grade == null) {
        toGrade.push({ q: r.q.id, id, line: poolLine([r.q.id, canonical, '', r.q.request, c.title, c.type, `candidate ${i + 1}`]) });
        return;
      }
      grades.push(grade);
      if (grade >= 2) recoverable.add(r.q.id);
    });
  }
  const relevant = grades.filter((g) => g >= 2).length;
  return { toGrade, graded: grades.length, relevant, share: grades.length ? relevant / grades.length : null, questions: asked.size, recoverable: recoverable.size };
}

/** One line per resource and question, picks first, in the order seen. */
async function writePool(entries) {
  const seen = new Set();
  const lines = entries.filter((e) => !seen.has(`${e.q} ${e.id}`) && seen.add(`${e.q} ${e.id}`)).map((e) => e.line);
  if (!lines.length) return;
  await mkdir(new URL('.', POOL), { recursive: true });
  await writeFile(POOL, ['query_id\turl\tgrade\trequest\ttitle\ttype\torigin', ...lines].join('\n') + '\n');
  console.log(`wrote backend/eval-data/guide-pool.tsv: ${lines.length} results to grade`);
}

async function handWritten() {
  const placeholder = '- Relevance of the picks, judged by hand: _to be added after the run (top picks relevant / not relevant)_.';
  let previous;
  try {
    previous = await readFile(OUTPUT, 'utf8');
  } catch {
    return placeholder;
  }
  const start = previous.indexOf(HAND_WRITTEN_START);
  const end = previous.indexOf(HAND_WRITTEN_END);
  if (start < 0 || end < start) return placeholder;
  return previous.slice(start + HAND_WRITTEN_START.length, end).trim() || placeholder;
}
const notes = await handWritten();

// Every run counts in the summary; the per-query table shows the first run.
const all = runs.flat();
const ok = all.filter((r) => r.a);
const count = (pred) => ok.filter(pred).length;
const kept = ok.reduce((n, r) => n + aiPicks(r.a), 0);
const rejected = ok.reduce((n, r) => n + (r.a.rejected ?? 0), 0);
const fromCache = ok.filter((r) => r.cached).length;
const withPrompt = count((r) => Boolean(r.a.prompt));
// Reason check (ADR 0016) over AI picks: SUPPORTED / REMOVED / NOT_CHECKED.
const reasonChecks = ok.flatMap((r) => r.a.recommendations.filter((x) => x.aiReasoned).map((x) => x.reasonCheck ?? 'NOT_CHECKED'));
const reasonCount = (v) => reasonChecks.filter((c) => c === v).length;
const latencies = ok.filter((r) => !r.cached).map((r) => r.ms).sort((x, y) => x - y);
const pct = (p) => (latencies.length ? latencies[Math.min(latencies.length - 1, Math.floor((p / 100) * latencies.length))] : 0);
const model = ok.find((r) => r.a.model)?.a.model;
const aiChecks = (a) => a.recommendations.filter((x) => x.aiReasoned).flatMap((x) => x.checks ?? []);
const confirmed = ok.reduce((n, r) => n + aiChecks(r.a).filter((c) => c.status === 'CONFIRMED').length, 0);
const notConfirmed = ok.reduce((n, r) => n + aiChecks(r.a).filter((c) => c.status === 'NOT_CONFIRMED').length, 0);
const withConstraints = count((r) => (r.a.constraints ?? []).length > 0);
const passed = all.filter(passes).length;
const checkCell = (a) => {
  const c = aiChecks(a);
  return c.length ? `${c.filter((x) => x.status === 'CONFIRMED').length}/${c.length}` : '–';
};

/** The broken expectations of one adversarial answer; empty when it passed. */
function failures(answer, expect) {
  const lower = (x) => String(x ?? '').toLowerCase();
  const picks = answer.recommendations.filter((x) => x.aiReasoned);
  const promptText = lower(answer.prompt?.text);
  const shown = [...picks.map((x) => lower(x.reason)), promptText];
  const out = [];
  if (expect.noPrompt && answer.prompt) out.push('a prompt was shown');
  if (expect.noPickTitled && picks.some((x) => lower(x.title).includes(lower(expect.noPickTitled)))) {
    out.push(`picked "${expect.noPickTitled}"`);
  }
  for (const t of expect.noText ?? []) if (shown.some((s) => s.includes(lower(t)))) out.push(`shows "${t}"`);
  for (const t of expect.noPromptText ?? []) if (promptText.includes(lower(t))) out.push(`prompt shows "${t}"`);
  // A claim the user asked for may stay in a reason only when the source backs it: its own title or description
  // (the text the quote and the reason check use), or a constraint check confirmed from the source.
  for (const t of expect.unsupportedClaimsRemoved ?? []) {
    for (const x of picks) {
      const inSource = [x.title, x.description, x.quote].some((s) => lower(s).includes(lower(t)));
      const confirmed = (x.checks ?? []).some((c) => c.status === 'CONFIRMED' && lower(c.constraint).includes(lower(t)));
      if (lower(x.reason).includes(lower(t)) && !inSource && !confirmed) out.push(`reason of "${x.title}" claims "${t}" without source support`);
    }
  }
  return out;
}

if (adversarial) {
  // A search-only answer (no model, or its budget spent) passes every expectation without testing a defence.
  const verdict = (r) => {
    if (!r.a) return [r.error];
    if (r.a.status === 'LLM_UNAVAILABLE') return ['inconclusive: no model answered (LLM_UNAVAILABLE)'];
    return failures(r.a, r.q.expect);
  };
  const results = rows.map((r) => ({ ...r, failed: verdict(r) }));
  const passed = results.filter((r) => r.failed.length === 0).length;
  const report = `# Guide adversarial evaluation

Generated by \`scripts/eval-guide.mjs --adversarial\` on ${new Date().toISOString()} against \`${base}\`.
Cases: [docs/eval/guide-adversarial.json](eval/guide-adversarial.json) (${queries.length} hostile questions, written before the run).

These are hostile **questions**: what anyone can put in a shared \`?ask=\` link. Hostile **source text** (a description
written to be picked) cannot be planted in live sources; it is covered by \`AskGuideTest\` and \`GuideValidatorTest\`.

**Passed: ${passed} / ${results.length}.**

A case answered without a model (\`LLM_UNAVAILABLE\`) counts as a failure: it tests nothing.

| id | request | status | AI picks | prompt | result |
|---|---|---|---|---|---|
${results.map((r) => `| ${r.q.id} | ${esc(r.q.request)} | ${r.a?.status ?? r.error} | ${r.a ? aiPicks(r.a) : '–'} | ${r.a?.prompt ? 'yes' : '–'} | ${r.failed.length ? `FAIL: ${esc(r.failed.join('; '))}` : 'pass'} |`).join('\n')}
`;
  await writeFile(OUTPUT, report);
  console.log(`wrote docs/evaluation-adversarial.md: ${passed} / ${results.length} passed`);
  process.exit(passed === results.length ? 0 : 1);
}

/** The timeline of a resource as the backend stores it: creation day and event start, each a day or null. */
async function timeline(id) {
  const res = await fetch(`${base}/api/v1/resources/${id}`);
  if (!res.ok) return null;
  const t = (await res.json()).card.timeline;
  const day = (f) => f?.value?.slice(0, 10) ?? null;
  return { published: day(t.published), eventStart: day(t.eventStart) };
}

/** The broken pass rules (ADR 0032 §10) of one intents case; empty when it passed. */
async function intentFailures(answer, expect) {
  const out = [];
  const lower = (x) => String(x ?? '').toLowerCase();
  const picks = answer.recommendations.filter((x) => x.aiReasoned || answer.intent === 'WHEN');
  if ('clarify' in expect && (answer.status === 'NEEDS_CLARIFICATION') !== expect.clarify) {
    out.push(expect.clarify ? `answered (${answer.status}) instead of asking back` : 'asked back on a clear question');
  }
  if (expect.intent && answer.intent !== expect.intent) out.push(`intent ${answer.intent ?? 'none'}, expected ${expect.intent}`);
  for (const s of expect.subjects ?? []) if (!(answer.subjects ?? []).some((x) => lower(x) === lower(s))) out.push(`subject "${s}" not read`);
  if (expect.window && !answer.window) out.push('no window resolved');
  if (expect.intent && answer.status !== 'NEEDS_CLARIFICATION') {
    const answered = picks.length || answer.comparison || answer.explanation?.sentences?.length;
    if (!answered) out.push(`nothing answered (${answer.status})`);
  }
  switch (expect.intent) {
    case 'NEWEST': {
      const days = await Promise.all(picks.map(async (x) => (await timeline(x.resourceId))?.published));
      if (days.some((d) => !d)) out.push('a pick has no creation date');
      if (answer.window && days.some((d) => d && (d < answer.window.from || d > answer.window.to))) out.push('a pick born outside the window');
      if (days.some((d, i) => i > 0 && d && days[i - 1] && d > days[i - 1])) out.push('picks not newest first');
      break;
    }
    case 'ALTERNATIVES':
      for (const s of expect.subjects ?? []) if (picks.some((x) => lower(x.title).includes(lower(s)))) out.push(`"${s}" itself is a pick`);
      break;
    case 'COMPARE': {
      const c = answer.comparison;
      if (!c) out.push('no comparison');
      else {
        if (c.subjects.length !== (expect.subjects ?? []).length) out.push(`${c.subjects.length} columns for ${(expect.subjects ?? []).length} subjects`);
        if (c.rows.some((r) => r.cells.some((cell) => cell.text && (!cell.quote || cell.reasonCheck === 'REMOVED')))) out.push('a cell without a gated quote');
      }
      break;
    }
    case 'WHAT_IS':
      if (!answer.explanation?.sentences?.length) out.push('no explanation');
      else if (answer.explanation.sentences.some((s) => !s.quote || s.reasonCheck === 'REMOVED')) out.push('a sentence without a gated quote');
      break;
    case 'WHEN': {
      const first = picks[0];
      const stated = first?.reason?.match(/\d{4}-\d{2}-\d{2}/)?.[0];
      const record = first ? await timeline(first.resourceId) : null;
      if (!stated) out.push('no date in the answer');
      else if (stated !== record?.eventStart && stated !== record?.published) out.push(`date ${stated} differs from the record`);
      if (expect.eventsOnly && picks.some((x) => x.type !== 'EVENT')) out.push('a result that is not an EVENT');
      break;
    }
    case 'LEARN':
      if (picks.some((x) => x.type !== 'COURSE')) out.push('a pick that is not a COURSE');
      break;
    default:
  }
  return out;
}

if (intents) {
  const verdicts = await Promise.all(rows.map(async (r) => {
    if (!r.a) return [r.error];
    if (r.a.status === 'LLM_UNAVAILABLE') return ['inconclusive: no model answered (LLM_UNAVAILABLE)'];
    return intentFailures(r.a, r.q.expect);
  }));
  const results = rows.map((r, i) => ({ ...r, failed: verdicts[i] }));
  const passed = results.filter((r) => r.failed.length === 0).length;
  const rel = relevance(rows.filter((r) => r.a));
  await writePool(rel.toGrade);
  const report = `# Guide intents evaluation

Generated by \`scripts/eval-guide.mjs --intents\` on ${new Date().toISOString()} against \`${base}\`.
Cases: [docs/eval/guide-intents.json](eval/guide-intents.json) (${queries.length} questions written before the run: at least two
per intent and seven that must or must not be asked back, [ADR 0032 §10](adr/guide/0032-guide-intents.md)).

**Passed: ${passed} / ${results.length}.** Answers from the cache: ${results.filter((r) => r.cached).length}.
${rel.judged ? `AI picks over ${rel.judged} judged questions: P@3 ${fmt(rel.p3)}, nDCG@3 ${fmt(rel.n3)}.` : 'Relevance: no judged question yet (docs/eval/guide-qrels.tsv).'}

Pass rules: the intent and subjects are read as expected; NEWEST: every pick born inside the window, newest first;
ALTERNATIVES: the subject is not a pick; COMPARE: one column per subject, no cell without a gated quote; WHAT_IS: every
sentence quoted; WHEN: the date equals the stored record, and an event question shows events only; LEARN: every pick is a COURSE; clarify cases: asked back
exactly when expected. A case answered without a model (\`LLM_UNAVAILABLE\`) fails: it tests nothing.

| id | request | status | intent | result |
|---|---|---|---|---|
${results.map((r) => `| ${r.q.id} | ${esc(r.q.request)} | ${r.a?.status ?? r.error} | ${r.a?.intent ?? '–'} | ${r.failed.length ? `FAIL: ${esc(r.failed.join('; '))}` : 'pass'} |`).join('\n')}
`;
  await writeFile(OUTPUT, report);
  console.log(`wrote docs/evaluation-intents.md: ${passed} / ${results.length} passed`);
  process.exit(passed === results.length ? 0 : 1);
}

const fresh = ok.filter((r) => !r.cached);
const rel = relevance(fresh);
const cand = candidateRelevance(all);
await writePool([...rel.toGrade, ...cand.toGrade]);

// Go / no-go figures for later retrieval changes (docs/evaluation.md, "Clean baseline").
const expecting = all.filter((r) => !r.q.expectNoPick);
const answerRate = expecting.length ? expecting.filter(passes).length / expecting.length : null;
const empty = all.filter((r) => r.q.expectNoPick);
const stayedEmpty = empty.filter(passes).length;
const http429 = all.filter((r) => r.http429 || r.pool?.http429).length;
const source429 = all.reduce((n, r) => n + rateLimited(r.a?.sources) + rateLimited(r.pool?.sources), 0);
const pctLabel = (x) => (x == null ? '–' : `${Math.round(x * 100)} %`);

/** Per question over the runs: how many answered, and whether the answer or the top pick changed. */
const stability = queries.map((q) => {
  const mine = runs.map((rs) => rs.find((r) => r.q.id === q.id)).filter((r) => r?.a && !r.cached && r.a.status !== 'LLM_UNAVAILABLE');
  const verdicts = new Set(mine.map((r) => answered(r.a)));
  const tops = new Set(mine.map((r) => r.a.recommendations.find((x) => x.aiReasoned)?.resourceId).filter(Boolean));
  return { id: q.id, runs: mine.length, answered: mine.filter((r) => answered(r.a)).length, flipped: verdicts.size > 1, topFlipped: tops.size > 1 };
});
const compared = stability.filter((x) => x.runs > 1);
const flips = compared.filter((x) => x.flipped).length;
const topFlips = compared.filter((x) => x.topFlipped).length;
const answeredCell = (id) => {
  const x = stability.find((s) => s.id === id);
  return repeat > 1 ? ` | ${x.answered}/${x.runs}${x.flipped ? ' flip' : ''}${x.topFlipped ? ' top-flip' : ''}` : '';
};
const problems = all.flatMap((r, i) => {
  const run = repeat > 1 ? ` (run ${Math.floor(i / queries.length) + 1})` : '';
  const listed = [...sourceProblems(r.a?.sources).map((s) => `${s.sourceId} ${s.outcome}`),
    ...sourceProblems(r.pool?.sources).map((s) => `pool search: ${s.sourceId} ${s.outcome}`)];
  if (r.error) listed.unshift(r.error);
  if (r.pool?.error) listed.push(`pool search: ${r.pool.error}`);
  return listed.length ? [`- ${r.q.id}${run}: ${listed.join(', ')}`] : [];
});
const expected = (q) => (q.expectNoPick ? 'empty' : 'answer');

/** How to run the clean baseline and what later changes must show; regenerated with the report. */
const BASELINE = `The baseline decides whether query fan-out (C) or an extra guide round (D) get built at all, so it runs once, clean,
on staging, before either. Never on prod.

Before the run, for that day only, in staging's \`app.env\` or chart values (back to the usual values the day after):

| Setting | For the run | Staging today | Why |
|---|---|---|---|
| \`H2_GUIDE_CACHE_TTL\` (\`app.guideCacheTtl\`) | \`PT1S\` | \`PT1S\` | every answer is a real model call |
| \`H2_GUIDE_DAILY_MODEL_CALLS\` (\`app.dailyLimits.guideModelCalls\`) | 1000 | 150 | 50 questions x 3 runs x 3 calls = 450, up to 900 with retries |
| \`H2_GUIDE_CLIENT_DAILY_QUESTIONS\` (\`app.dailyLimits.guideQuestions\`) | at least 200 | 1000 | 150 questions from one address |
| \`H2_LIVE_WEB_GUEST_GUIDE\` (\`app.dailyLimits.webSearches.guide\`) | at least 200 | 1000 | n01 probably hit the guest limit of 3 on 29 Oct |
| \`H2_LIVE_WEB_GUEST_SEARCH\` (\`app.dailyLimits.webSearches.search\`) | at least 200 | 1000 | the pool searches of questions with no answer |
| \`H2_LIVE_WEB_DAILY_CREDITS\` (\`app.dailyLimits.webSearchCredits\`) | 400 | 200 | web searches of the guide and of the pool searches |

The backend needs its GitHub token (\`H2_GITHUB_TOKEN\`): the script keeps to 25 live GitHub searches a minute, which only
an authenticated token allows. Start after the nightly run (01:41 UTC) has finished.

The run, from the repository root (about 45 minutes):

\`\`\`
node scripts/eval-guide.mjs "$H2_STAGING_URL" --repeat 3
\`\`\`

Then:

1. Grade \`backend/eval-data/guide-pool.tsv\` with the grades of [eval/README.md](eval/README.md): the AI picks, and the
   candidates of every question with no answer. Append the first three columns to \`docs/eval/guide-qrels.tsv\`,
   then delete the pool: the script never overwrites a pool with grades in it.
2. \`node scripts/eval-guide.mjs --rescore\` rebuilds this report from the saved answers
   (\`backend/eval-data/guide-runs.json\`) with the new grades, with no new request.
3. Write the notes, and move the report to \`docs/eval/\` when a new run takes its place.

What the figures decide:

- **Choosing or retrieval.** When most questions with no answer already have a candidate graded relevant, the guide
  found the resource and did not pick it: fix the chooser and the planner's wording, not C or D. When few have one,
  retrieval is the gap and C is worth trying.
- **Noise.** A change counts only when it is bigger than the flips between the baseline's own runs.
- **C (fan-out)** goes ahead only if, on the same set and settings: the answer rate goes up, P@3 does not go down, the
  share of relevant candidates goes up, p90 grows by 1.5 s at most, and there are zero 429s.
- **D (extra round)** goes ahead only if: the answer rate goes up, P@3 does not go down, there are zero 429s, and the
  p90 of the questions that trigger the extra round, reported on its own, stays under the target set before D is built.`;

const md = `# Guide evaluation

Generated by \`scripts/eval-guide.mjs${core ? ' --core' : ''}${repeat > 1 ? ` --repeat ${repeat}` : ''}\` on ${runAt} against \`${runBase}\`${rescore ? ` (scored again on ${new Date().toISOString()} with the judgements of that day)` : ''}.
Query set: [docs/eval/guide-queries.json](eval/guide-queries.json) (${queries.length} fixed queries, written before the run, sent exactly as written)${repeat > 1 ? `, ${repeat} runs` : ''}.
Models: ${model ? `planning \`${model.fastModel}\`, choosing \`${model.reasoningModel}\`, provider \`${model.provider}\`` : 'no model answered'}.

## Go / no-go figures

The figures later retrieval changes are measured against (see "Clean baseline" below).

| Figure | Value |
|---|---|
| Answer rate: questions that expect an answer, answered fresh by the model | ${expecting.filter(passes).length} / ${expecting.length} (${pctLabel(answerRate)}) |
| Questions that must stay empty and did | ${stayedEmpty} / ${empty.length} |
| AI picks P@3 / nDCG@3 (judged questions) | ${rel.judged ? `${fmt(rel.p3)} / ${fmt(rel.n3)} (${rel.judged} judged answers)` : '– (no judgements in docs/eval/guide-qrels.tsv)'} |
| Candidates of unanswered questions graded relevant | ${cand.graded ? `${cand.relevant} / ${cand.graded} (${pctLabel(cand.share)}); ${cand.recoverable} of ${cand.questions} questions have one` : `– (${cand.questions} questions, none graded yet)`} |
| End-to-end latency p90 (cache hits excluded) | ${pct(90)} ms |
| 429s: HTTP / sources rate-limited | ${http429} / ${source429} |
| Answers that flipped between runs / top pick changed | ${compared.length ? `${flips} / ${topFlips} of ${compared.length}` : '– (one run; use --repeat)'} |

## Summary

| Metric | Value |
|---|---|
| Queries answered (HTTP 200) | ${ok.length} / ${all.length} |
| Passed: answered fresh by the model, or empty as expected | ${passed} / ${all.length} |
| Status OK / PARTIAL / LLM_UNAVAILABLE / NO_CANDIDATES / NEEDS_CLARIFICATION | ${count((r) => r.a.status === 'OK')} / ${count((r) => r.a.status === 'PARTIAL')} / ${count((r) => r.a.status === 'LLM_UNAVAILABLE')} / ${count((r) => r.a.status === 'NO_CANDIDATES')} / ${count((r) => r.a.status === 'NEEDS_CLARIFICATION')} |
| AI picks kept by the validator | ${kept} |
| AI suggestions rejected by the validator | ${rejected} |
| Answers served from the cache (expected 0) | ${fromCache} / ${ok.length} |
| Answers with a ready-to-use prompt | ${withPrompt} / ${ok.length} |
| Reason check on AI picks: supported / removed / not checked | ${reasonCount('SUPPORTED')} / ${reasonCount('REMOVED')} / ${reasonCount('NOT_CHECKED')} |
| Queries where the planner found constraints | ${withConstraints} / ${ok.length} |
| Constraint checks on AI picks: confirmed / not confirmed | ${confirmed} / ${notConfirmed} |
| End-to-end latency p50 / p90 / max (cache hits excluded) | ${pct(50)} ms / ${pct(90)} ms / ${latencies.at(-1) ?? 0} ms |

Per-model latency (planning vs choosing) is in the backend log lines starting with \`guide timings:\`.

## Per query${repeat > 1 ? ' (first run)' : ''}

| id | group | request | expected | status | kept | rejected | constraints | checks confirmed | latency${repeat > 1 ? ' | runs answered' : ''} | top pick |
|---|---|---|---|---|---|---|---|---|---|${repeat > 1 ? '---|' : ''}---|
${rows.map((r) => r.error
  ? `| ${r.q.id} | ${r.q.path ?? 'any'} | ${esc(r.q.request)} | ${expected(r.q)} | ${r.error} | – | – | – | – | ${r.ms} ms${answeredCell(r.q.id)} | – |`
  : `| ${r.q.id} | ${r.q.path ?? 'any'} | ${esc(r.q.request)} | ${expected(r.q)} | ${r.a.status} | ${aiPicks(r.a)} | ${r.a.rejected ?? 0} | ${esc((r.a.constraints ?? []).join('; ') || '–')} | ${checkCell(r.a)} | ${r.ms} ms${r.cached ? ' (cache)' : ''}${answeredCell(r.q.id)} | ${esc(r.a.recommendations.find((x) => x.aiReasoned)?.title ?? (r.a.comparison ? '(comparison)' : r.a.explanation ? '(explanation)' : '– (no AI pick)'))} |`).join('\n')}

## Sources that did not succeed

${problems.length ? problems.join('\n') : 'None: every source of every answer succeeded.'}

## How to run

\`node scripts/eval-guide.mjs <baseUrl>\` against a backend started with \`H2_GUIDE_CACHE_TTL=PT1S\` (staging), so every answer is a real model call and the questions are sent exactly as written.
\`--core\` runs the 20 core questions only (the nightly run); \`--repeat <n>\` runs the set n times to count flips.
Each question counts against the per-address daily quota (\`H2_GUIDE_CLIENT_DAILY_QUESTIONS\`) and spends about 3 of the guide's daily model calls (\`H2_GUIDE_DAILY_MODEL_CALLS\`); the backend under test sets both to cover the run (see "Clean baseline" below).
\`--intents\` runs the intent cases (it writes \`docs/evaluation-intents.md\`), \`--adversarial\` the hostile questions ([evaluation-adversarial.md](evaluation-adversarial.md)).
When an answer carries the \`GUIDE_DAILY_QUOTA\` notice, or 3 answers in a row come without a model, the script stops and leaves this file unchanged.
The section under "Notes on this run" is hand-written and kept by the script; the rest is regenerated.

## Clean baseline

${BASELINE}

## How to read this

- "Answered" means the model gave an answer of the question's kind: an AI pick, a comparison, an explanation, or a dated record. "Expected empty" questions pass when they stay empty; each one says why in the query file.
- "Checks confirmed" is confirmed / total constraint checks over the AI picks. Not confirmed means the resource's own text does not show it, not that it is false.
- Previous runs are in [docs/eval/](eval/).
- "(cache)" marks an answer served from the cache (the backend was not run with \`H2_GUIDE_CACHE_TTL=PT1S\`); its latency is left out of the percentiles.
- P@3 is the share of the first three AI picks graded relevant (2 or 3) in [eval/guide-qrels.tsv](eval/guide-qrels.tsv); nDCG@3 weighs them by grade. Picks not graded yet are written to \`backend/eval-data/guide-pool.tsv\` and count as not relevant.
- "Candidates graded relevant": for each question with no answer, the script sends the answer's search terms to \`GET /api/v1/search\` (24 results) and writes them to the pool. It is close to the guide's own 24 candidates, not the same list (no intent filter, sort or web query). Once graded, it tells a retrieval problem (no good candidate) from a choosing problem (a good candidate the model left out).
- A flip is a question answered in one run and not in another; "top-flip" is a first AI pick that differs between runs.
- "Kept" counts only AI-reasoned picks that passed the evidence check. Plain search results shown as a fallback count 0.
- "Top pick" is the first AI-reasoned pick; "no AI pick" means the model chose nothing or everything was rejected.
- \`b14\` is in Italian, to check that the planning model turns any language into English search terms.

## Notes on this run

${HAND_WRITTEN_START}
${notes}
${HAND_WRITTEN_END}
`;

await writeFile(OUTPUT, md);
console.log(`wrote docs/evaluation.md: ${passed} / ${all.length} passed`);
if (minPass != null && passed < minPass * all.length) {
  console.error(`Pass rate ${passed} / ${all.length} is below --min-pass ${minPass}.`);
  process.exit(1);
}
