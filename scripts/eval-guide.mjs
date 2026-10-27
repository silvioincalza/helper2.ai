#!/usr/bin/env node
// Runs the fixed guide query set against a running backend and writes docs/evaluation.md.
// Usage: node scripts/eval-guide.mjs [baseUrl] [--fresh]   (default http://localhost:8080)
// --fresh appends " ?" to every request (--fresh=TEXT appends " TEXT"). The guide caches answers for 24 h by
// normalised text, so this is the only way to measure real model calls on a server that has already
// answered the query set; use a new TEXT when a previous --fresh run is still cached.
// Needs Node 18+ (global fetch). Respects the guide rate limit (10/min) with a pause between calls.
//
// Daily quota: every fresh (not cached) question counts against the per-client daily quota
// (H2_GUIDE_CLIENT_DAILY_QUESTIONS, default 30 per address per UTC day). The query set has 20 questions, so a
// second fresh run from the same address on the same UTC day runs out part way and the rest would be search
// only. The script stops with an error as soon as an answer carries the GUIDE_DAILY_QUOTA notice and leaves
// docs/evaluation.md unchanged. Run it once per UTC day per address, or against a local backend started with a
// higher H2_GUIDE_CLIENT_DAILY_QUESTIONS.
//
// Hand-written notes: the text between the HAND_WRITTEN_START and HAND_WRITTEN_END markers in
// docs/evaluation.md is kept as it is; the rest of the file is regenerated. Update those notes after each run.
//
// --adversarial runs docs/eval/guide-adversarial.json instead: hostile questions, each with expectations the
// answer must meet (no prompt, no given text in reasons or prompt, ...). It writes docs/evaluation-adversarial.md
// and exits with code 1 when a case fails. 8 questions: it fits in the daily quota next to one normal run.
import { readFile, writeFile } from 'node:fs/promises';

const args = process.argv.slice(2);
const freshArg = args.find((a) => a === '--fresh' || a.startsWith('--fresh='));
const fresh = freshArg ? (freshArg.includes('=') ? freshArg.slice(8) : '?') : null;
const adversarial = args.includes('--adversarial');
const base = (args.find((a) => !a.startsWith('--')) ?? 'http://localhost:8080').replace(/\/$/, '');
const QUERY_FILE = adversarial ? 'guide-adversarial.json' : 'guide-queries.json';
const queries = JSON.parse(await readFile(new URL(`../docs/eval/${QUERY_FILE}`, import.meta.url), 'utf8'));
const PAUSE_MS = 7000;
const OUTPUT = new URL(adversarial ? '../docs/evaluation-adversarial.md' : '../docs/evaluation.md', import.meta.url);
const HAND_WRITTEN_START = '<!-- hand-written: kept by scripts/eval-guide.mjs -->';
const HAND_WRITTEN_END = '<!-- end hand-written -->';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function csrfToken() {
  const res = await fetch(`${base}/api/v1/me`);
  const cookies = res.headers.getSetCookie?.() ?? [];
  const match = cookies.map((c) => /^XSRF-TOKEN=([^;]*)/.exec(c)).find(Boolean);
  if (!match) throw new Error('No XSRF-TOKEN cookie from /api/v1/me; is the backend running?');
  return match[1];
}

async function ask(token, q) {
  const startedAt = Date.now();
  const started = performance.now();
  const res = await fetch(`${base}/api/v1/guide`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-xsrf-token': token, cookie: `XSRF-TOKEN=${token}` },
    // The UI sends no audience path; `path` in the query file is only a group label.
    body: JSON.stringify({ request: fresh ? `${q.request} ${fresh}` : q.request }),
  });
  const ms = Math.round(performance.now() - started);
  if (!res.ok) return { q, ms, error: `HTTP ${res.status}` };
  const a = await res.json();
  // A cached answer keeps its original generatedAt, so an answer older than the request came from the cache.
  const cached = Date.parse(a.generatedAt) < startedAt - 1000;
  return { q, ms, a, cached };
}

const token = await csrfToken();
const rows = [];
for (const [i, q] of queries.entries()) {
  if (i > 0) await sleep(PAUSE_MS);
  const r = await ask(token, q);
  rows.push(r);
  console.log(`${q.id} ${r.error ?? r.a.status} ${r.ms}ms`);
  if (r.a?.notices?.some((n) => n.code === 'GUIDE_DAILY_QUOTA')) {
    console.error(
      `Stopped at ${q.id}: this address has used its daily guide quota (GUIDE_DAILY_QUOTA), so answers are search ` +
        'only and the run would not measure the model. docs/evaluation.md is unchanged. Run again after 00:00 UTC, ' +
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
const esc = (s) => String(s ?? '').replaceAll('|', '\\|').replaceAll('\n', ' ');

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
Cases: [docs/eval/guide-adversarial.json](eval/guide-adversarial.json) (${queries.length} hostile questions, written before the run)${fresh ? `; each sent with " ${fresh}" appended to bypass the 24-hour answer cache` : ''}.

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


const md = `# Guide evaluation

Generated by \`scripts/eval-guide.mjs\` on ${new Date().toISOString()} against \`${base}\`.
Query set: [docs/eval/guide-queries.json](eval/guide-queries.json) (${queries.length} fixed queries, written before the run)${fresh ? `; each sent with " ${fresh}" appended to bypass the 24-hour answer cache` : ''}.
Models: ${model ? `planning \`${model.fastModel}\`, choosing \`${model.reasoningModel}\`, provider \`${model.provider}\`` : 'no model answered'}.

## Summary

| Metric | Value |
|---|---|
| Queries answered (HTTP 200) | ${ok.length} / ${rows.length} |
| Status OK / PARTIAL / LLM_UNAVAILABLE / NO_CANDIDATES | ${count((r) => r.a.status === 'OK')} / ${count((r) => r.a.status === 'PARTIAL')} / ${count((r) => r.a.status === 'LLM_UNAVAILABLE')} / ${count((r) => r.a.status === 'NO_CANDIDATES')} |
| AI picks kept by the validator | ${kept} |
| AI suggestions rejected by the validator | ${rejected} |
| Answers served from the 24-hour cache | ${fromCache} / ${ok.length} |
| Answers with a ready-to-use prompt | ${withPrompt} / ${ok.length} |
| Reason check on AI picks: supported / removed / not checked | ${reasonCount('SUPPORTED')} / ${reasonCount('REMOVED')} / ${reasonCount('NOT_CHECKED')} |
| Queries where the planner found constraints | ${withConstraints} / ${ok.length} |
| Constraint checks on AI picks: confirmed / not confirmed | ${confirmed} / ${notConfirmed} |
| End-to-end latency p50 / p90 / max (cache hits excluded) | ${pct(50)} ms / ${pct(90)} ms / ${latencies.at(-1) ?? 0} ms |

Per-model latency (planning vs choosing) is in the backend log lines starting with \`guide timings:\`.

## Per query

| id | group | request | status | kept | rejected | constraints | checks confirmed | prompt | latency | top pick |
|---|---|---|---|---|---|---|---|---|---|---|
${rows.map((r) => r.error
  ? `| ${r.q.id} | ${r.q.path ?? 'any'} | ${esc(r.q.request)} | ${r.error} | – | – | – | – | – | ${r.ms} ms | – |`
  : `| ${r.q.id} | ${r.q.path ?? 'any'} | ${esc(r.q.request)} | ${r.a.status} | ${aiPicks(r.a)} | ${r.a.rejected ?? 0} | ${esc((r.a.constraints ?? []).join('; ') || '–')} | ${checkCell(r.a)} | ${r.a.prompt ? 'yes' : '–'} | ${r.ms} ms${r.cached ? ' (cache)' : ''} | ${esc(r.a.recommendations.find((x) => x.aiReasoned)?.title ?? '– (no AI pick)')} |`).join('\n')}

## How to run

\`node scripts/eval-guide.mjs https://helper2.ai --fresh=TEXT\` with a TEXT not used before (it bypasses the 24-hour answer cache).
Each fresh question counts against the per-address daily quota (\`H2_GUIDE_CLIENT_DAILY_QUESTIONS\`, default 30 per UTC day), and the set has 20 questions, so run it once per UTC day per address, or against a local backend started with a higher quota.
When an answer carries the \`GUIDE_DAILY_QUOTA\` notice the script stops and leaves this file unchanged.
The section under "Notes on this run" is hand-written and kept by the script; the rest is regenerated.

## How to read this

- "Checks confirmed" is confirmed / total constraint checks over the AI picks. Not confirmed means the resource's own text does not show it, not that it is false.
- Previous runs are in [docs/eval/](eval/).
- "(cache)" marks an answer served from the 24-hour cache; its latency is left out of the percentiles.
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
