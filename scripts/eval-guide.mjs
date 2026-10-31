#!/usr/bin/env node
// Runs a fixed guide question set against a running backend and writes a report.
// Usage: node scripts/eval-guide.mjs [baseUrl] [--adversarial | --intents]   (default http://localhost:8080)
// Needs Node 18+ (global fetch). Respects the guide rate limit (10/min) with a pause between calls.
//
// Cache: every run must measure real model calls, so the backend under test runs with
// H2_GUIDE_CACHE_TTL=PT1S (staging): an answer expires before the next question is sent, and a rerun of the
// same set is never served from the cache. An answer that still comes from the cache (generatedAt older than the
// request) is marked "(cache)", left out of the latency figures and counted in the summary.
//
// Daily quota: every question counts against the per-client daily quota (H2_GUIDE_CLIENT_DAILY_QUESTIONS, default 30
// per address per UTC day). The default set has 20 questions, --intents 22 and --adversarial 8; the staging backend
// for scheduled runs sets a quota that covers all three. The script stops with an error as soon as an answer carries
// the GUIDE_DAILY_QUOTA notice and leaves the report unchanged.
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
// reports add P@3 and nDCG@3 of the AI picks. Picks no judge has graded are written to
// backend/eval-data/guide-pool.tsv (git-ignored), ready to be graded and copied into the qrels.
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { canonicalUrl, csrfToken, esc, fmt, mean, ndcgAt, postJson, precisionAt, readQrels, resourceId, sleep } from './eval-lib.mjs';

const args = process.argv.slice(2);
const adversarial = args.includes('--adversarial');
const intents = args.includes('--intents');
const base = (args.find((a) => !a.startsWith('--')) ?? 'http://localhost:8080').replace(/\/$/, '');
const QUERY_FILE = adversarial ? 'guide-adversarial.json' : intents ? 'guide-intents.json' : 'guide-queries.json';
const queries = JSON.parse(await readFile(new URL(`../docs/eval/${QUERY_FILE}`, import.meta.url), 'utf8'));
const qrels = await readQrels(new URL('../docs/eval/guide-qrels.tsv', import.meta.url));
const PAUSE_MS = 7000;
const OUTPUT = new URL(
  adversarial ? '../docs/evaluation-adversarial.md' : intents ? '../docs/evaluation-intents.md' : '../docs/evaluation.md',
  import.meta.url,
);
const POOL = new URL('../backend/eval-data/guide-pool.tsv', import.meta.url);
const HAND_WRITTEN_START = '<!-- hand-written: kept by scripts/eval-guide.mjs -->';
const HAND_WRITTEN_END = '<!-- end hand-written -->';
const REPORT = adversarial ? 'docs/evaluation-adversarial.md' : intents ? 'docs/evaluation-intents.md' : 'docs/evaluation.md';

async function ask(token, q) {
  const startedAt = Date.now();
  const started = performance.now();
  // The UI sends no audience path; `path` in the query file is only a group label.
  const res = await postJson(base, token, '/api/v1/guide', { request: q.request });
  const ms = Math.round(performance.now() - started);
  if (!res.ok) return { q, ms, error: `HTTP ${res.status}` };
  const a = await res.json();
  // A cached answer keeps its original generatedAt, so an answer older than the request came from the cache.
  const cached = Date.parse(a.generatedAt) < startedAt - 1000;
  return { q, ms, a, cached };
}

/** Grades of the AI picks of [answer] in order, null where no judge has graded the pick. */
function pickGrades(id, answer) {
  const judged = qrels?.get(id) ?? new Map();
  return answer.recommendations.filter((x) => x.aiReasoned).map((x) => judged.get(x.resourceId) ?? null);
}

/** P@3 and nDCG@3 of the AI picks over the judged queries, and the picks still to grade. */
function relevance(answered) {
  const judgedRows = answered.filter((r) => [...(qrels?.get(r.q.id)?.values() ?? [])].some((g) => g > 0));
  const p3 = mean(judgedRows.map((r) => precisionAt(pickGrades(r.q.id, r.a), 3)));
  const n3 = mean(judgedRows.map((r) => ndcgAt(pickGrades(r.q.id, r.a), [...qrels.get(r.q.id).values()], 3)).filter((x) => x != null));
  const toGrade = answered.flatMap((r) => r.a.recommendations.filter((x) => x.aiReasoned)
    .filter((x) => !qrels?.get(r.q.id)?.has(x.resourceId))
    .map((x) => [r.q.id, canonicalUrl(x.url) ?? x.url, '', r.q.request, x.title].map((c) => String(c).replaceAll('\t', ' ')).join('\t')));
  return { judged: judgedRows.length, p3, n3, toGrade };
}

async function writePool(lines) {
  if (!lines.length) return;
  await mkdir(new URL('.', POOL), { recursive: true });
  await writeFile(POOL, ['query_id\turl\tgrade\trequest\ttitle', ...lines].join('\n') + '\n');
  console.log(`wrote backend/eval-data/guide-pool.tsv: ${lines.length} picks to grade`);
}

const token = await csrfToken(base);
const rows = [];
for (const [i, q] of queries.entries()) {
  if (i > 0) await sleep(PAUSE_MS);
  const r = await ask(token, q);
  rows.push(r);
  console.log(`${q.id} ${r.error ?? r.a.status} ${r.ms}ms`);
  if (r.a?.notices?.some((n) => n.code === 'GUIDE_DAILY_QUOTA')) {
    console.error(
      `Stopped at ${q.id}: this address has used its daily guide quota (GUIDE_DAILY_QUOTA), so answers are search ` +
        `only and the run would not measure the model. ${REPORT} is unchanged. Run again after 00:00 UTC, ` +
        'from another address, or against a backend with a higher H2_GUIDE_CLIENT_DAILY_QUESTIONS.',
    );
    process.exit(1);
  }
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

const ok = rows.filter((r) => r.a);
const count = (pred) => ok.filter(pred).length;
const aiPicks = (a) => a.recommendations.filter((x) => x.aiReasoned).length;
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

const rel = relevance(ok);
await writePool(rel.toGrade);

const md = `# Guide evaluation

Generated by \`scripts/eval-guide.mjs\` on ${new Date().toISOString()} against \`${base}\`.
Query set: [docs/eval/guide-queries.json](eval/guide-queries.json) (${queries.length} fixed queries, written before the run).
Models: ${model ? `planning \`${model.fastModel}\`, choosing \`${model.reasoningModel}\`, provider \`${model.provider}\`` : 'no model answered'}.

## Summary

| Metric | Value |
|---|---|
| Queries answered (HTTP 200) | ${ok.length} / ${rows.length} |
| Status OK / PARTIAL / LLM_UNAVAILABLE / NO_CANDIDATES | ${count((r) => r.a.status === 'OK')} / ${count((r) => r.a.status === 'PARTIAL')} / ${count((r) => r.a.status === 'LLM_UNAVAILABLE')} / ${count((r) => r.a.status === 'NO_CANDIDATES')} |
| AI picks kept by the validator | ${kept} |
| AI suggestions rejected by the validator | ${rejected} |
| Answers served from the cache (expected 0) | ${fromCache} / ${ok.length} |
| Answers with a ready-to-use prompt | ${withPrompt} / ${ok.length} |
| Reason check on AI picks: supported / removed / not checked | ${reasonCount('SUPPORTED')} / ${reasonCount('REMOVED')} / ${reasonCount('NOT_CHECKED')} |
| Queries where the planner found constraints | ${withConstraints} / ${ok.length} |
| Constraint checks on AI picks: confirmed / not confirmed | ${confirmed} / ${notConfirmed} |
| End-to-end latency p50 / p90 / max (cache hits excluded) | ${pct(50)} ms / ${pct(90)} ms / ${latencies.at(-1) ?? 0} ms |
| AI picks P@3 / nDCG@3 (judged questions) | ${rel.judged ? `${fmt(rel.p3)} / ${fmt(rel.n3)} (${rel.judged} questions)` : '– (no judgements in docs/eval/guide-qrels.tsv)'} |

Per-model latency (planning vs choosing) is in the backend log lines starting with \`guide timings:\`.

## Per query

| id | group | request | status | kept | rejected | constraints | checks confirmed | prompt | latency | top pick |
|---|---|---|---|---|---|---|---|---|---|---|
${rows.map((r) => r.error
  ? `| ${r.q.id} | ${r.q.path ?? 'any'} | ${esc(r.q.request)} | ${r.error} | – | – | – | – | – | ${r.ms} ms | – |`
  : `| ${r.q.id} | ${r.q.path ?? 'any'} | ${esc(r.q.request)} | ${r.a.status} | ${aiPicks(r.a)} | ${r.a.rejected ?? 0} | ${esc((r.a.constraints ?? []).join('; ') || '–')} | ${checkCell(r.a)} | ${r.a.prompt ? 'yes' : '–'} | ${r.ms} ms${r.cached ? ' (cache)' : ''} | ${esc(r.a.recommendations.find((x) => x.aiReasoned)?.title ?? '– (no AI pick)')} |`).join('\n')}

## How to run

\`node scripts/eval-guide.mjs <baseUrl>\` against a backend started with \`H2_GUIDE_CACHE_TTL=PT1S\` (staging), so every answer is a real model call and the questions are sent exactly as written.
Each question counts against the per-address daily quota (\`H2_GUIDE_CLIENT_DAILY_QUESTIONS\`, default 30 per UTC day); the set has 20 questions, so the backend under test sets a quota that covers the run.
\`--intents\` runs the intent cases ([evaluation-intents.md](evaluation-intents.md)), \`--adversarial\` the hostile questions ([evaluation-adversarial.md](evaluation-adversarial.md)).
When an answer carries the \`GUIDE_DAILY_QUOTA\` notice the script stops and leaves this file unchanged.
The section under "Notes on this run" is hand-written and kept by the script; the rest is regenerated.

## How to read this

- "Checks confirmed" is confirmed / total constraint checks over the AI picks. Not confirmed means the resource's own text does not show it, not that it is false.
- Previous runs are in [docs/eval/](eval/).
- "(cache)" marks an answer served from the cache (the backend was not run with \`H2_GUIDE_CACHE_TTL=PT1S\`); its latency is left out of the percentiles.
- P@3 is the share of the first three AI picks graded relevant (2 or 3) in [eval/guide-qrels.tsv](eval/guide-qrels.tsv); nDCG@3 weighs them by grade. Picks not graded yet are written to \`backend/eval-data/guide-pool.tsv\` and count as not relevant.
- "Kept" counts only AI-reasoned picks that passed the evidence check. Plain search results shown as a fallback count 0.
- "Top pick" is the first AI-reasoned pick; "no AI pick" means the model chose nothing or everything was rejected.
- \`x01\` is a nonsense query. The honest result is NO_CANDIDATES or no AI picks.
- \`b14\` is in Italian, to check that the planning model turns any language into English search terms.

## Notes on this run

${HAND_WRITTEN_START}
${notes}
${HAND_WRITTEN_END}
`;

await writeFile(OUTPUT, md);
console.log('wrote docs/evaluation.md');
