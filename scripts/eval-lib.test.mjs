import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createServer } from 'node:http';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { EXIT_BUDGET_SPENT, EXIT_FAILED, EXIT_NETWORK_QUOTA, guideRunStop, noticeCodes } from './eval-lib.mjs';

const run = { id: 'b07', base: 'https://staging.example', report: 'docs/eval/guide-run.md' };
const answer = (status, ...codes) => ({ status, sources: [], notices: codes.map((code) => ({ code, message: `${code} message` })) });

test('the first answer with the budget notice stops the run with its own exit code', () => {
  const stop = guideRunStop({ ...run, answer: answer('LLM_UNAVAILABLE', 'GUIDE_BUDGET_SPENT'), withoutModel: 1 });

  assert.equal(stop.exitCode, EXIT_BUDGET_SPENT);
  assert.notEqual(EXIT_BUDGET_SPENT, EXIT_FAILED);
  assert.match(stop.message, /^Stopped at b07: the guide's daily model budget on https:\/\/staging\.example is used up \(GUIDE_BUDGET_SPENT\)/);
  assert.match(stop.message, /"GUIDE_BUDGET_SPENT message"/);
  assert.match(stop.message, /docs\/eval\/guide-run\.md is unchanged/);
});

test('the budget notice stops the run whatever the status of its answer', () => {
  assert.equal(guideRunStop({ ...run, answer: answer('NO_CANDIDATES', 'GUIDE_BUDGET_SPENT'), withoutModel: 0 }).exitCode, EXIT_BUDGET_SPENT);
});

test('the budget stop names the time the backend gives', () => {
  const spent = answer('LLM_UNAVAILABLE', 'GUIDE_BUDGET_SPENT');
  spent.notices[0].retryAt = '2026-11-09T14:00:00Z';

  assert.match(guideRunStop({ ...run, answer: spent, withoutModel: 1 }).message, /Run again after 2026-11-09T14:00:00Z /);
  assert.match(guideRunStop({ ...run, answer: answer('LLM_UNAVAILABLE', 'GUIDE_BUDGET_SPENT'), withoutModel: 1 }).message, /Run again when the budget opens /);
});

test('the first answer with the network quota notice stops the run with its own exit code and message', () => {
  const full = answer('LLM_UNAVAILABLE', 'GUIDE_NETWORK_QUOTA');
  full.notices[0].retryAt = '2026-11-10T00:00:00Z';

  const stop = guideRunStop({ ...run, answer: full, withoutModel: 1 });

  assert.equal(stop.exitCode, EXIT_NETWORK_QUOTA);
  assert.equal(new Set([EXIT_FAILED, EXIT_BUDGET_SPENT, EXIT_NETWORK_QUOTA]).size, 3);
  assert.match(stop.message, /^Stopped at b07: the runner's network has used its guide questions for today on https:\/\/staging\.example \(GUIDE_NETWORK_QUOTA\)/);
  assert.match(stop.message, /Run again after 2026-11-10T00:00:00Z, from another network/);
  assert.match(stop.message, /docs\/eval\/guide-run\.md is unchanged/);
  assert.doesNotMatch(stop.message, /model budget|this address/);
});

test('the daily quota of the address stops the run as a failure', () => {
  const stop = guideRunStop({ ...run, answer: answer('LLM_UNAVAILABLE', 'GUIDE_DAILY_QUOTA'), withoutModel: 1 });

  assert.equal(stop.exitCode, EXIT_FAILED);
  assert.match(stop.message, /this address has used its daily guide quota \(GUIDE_DAILY_QUOTA\)/);
  assert.match(stop.message, /Run again after 00:00 UTC,/);
});

test('three answers in a row without a model stop the run and blame the provider, not the budget', () => {
  const down = answer('LLM_UNAVAILABLE');

  assert.equal(guideRunStop({ ...run, answer: down, withoutModel: 2 }), null);
  const stop = guideRunStop({ ...run, answer: down, withoutModel: 3 });
  assert.equal(stop.exitCode, EXIT_FAILED);
  assert.match(stop.message, /3 answers in a row without a model/);
  assert.match(stop.message, /provider is down/);
  assert.doesNotMatch(stop.message, /H2_GUIDE_DAILY_MODEL_CALLS|are spent/);
});

test('an answer from the model, or a failed request, does not stop the run', () => {
  assert.equal(guideRunStop({ ...run, answer: answer('OK'), withoutModel: 0 }), null);
  assert.equal(guideRunStop({ ...run, answer: undefined, withoutModel: 0 }), null);
});

test('the notice codes of an answer are listed in order', () => {
  assert.deepEqual(noticeCodes(answer('OK', 'QUERY_EXPANDED', 'GUIDE_SUBJECT_NOT_FOUND')), ['QUERY_EXPANDED', 'GUIDE_SUBJECT_NOT_FOUND']);
  assert.deepEqual(noticeCodes(undefined), []);
});

const backendWithASpentBudget = () =>
  createServer((req, res) => {
    if (req.url === '/api/v1/me') {
      res.writeHead(200, { 'set-cookie': 'XSRF-TOKEN=t; Path=/' }).end('{}');
      return;
    }
    const spent = { ...answer('LLM_UNAVAILABLE', 'GUIDE_BUDGET_SPENT'), recommendations: [], generatedAt: new Date().toISOString() };
    res.writeHead(200, { 'content-type': 'text/event-stream' }).end(`event:answer\ndata:${JSON.stringify(spent)}\n\n`);
  });

const evalGuide = (base, env) =>
  new Promise((resolve) => {
    const script = fileURLToPath(new URL('./eval-guide.mjs', import.meta.url));
    execFile(process.execPath, [script, base, '--adversarial'], { env: { ...process.env, ...env } }, (error, stdout, stderr) =>
      resolve({ code: error?.code ?? 0, stdout, stderr }));
  });

test('the script stops at the first question when the backend says its budget is used up', async () => {
  const server = backendWithASpentBudget().listen(0);
  try {
    const base = `http://localhost:${server.address().port}`;

    const { code, stdout, stderr } = await evalGuide(base, { H2_EVAL_PAUSE_MS: '0', GITHUB_STEP_SUMMARY: '' });

    assert.equal(code, EXIT_BUDGET_SPENT);
    assert.equal(stdout.trim().split('\n').length, 1, 'one question asked');
    assert.match(stdout, /LLM_UNAVAILABLE \d+ms \| notices GUIDE_BUDGET_SPENT \|/);
    assert.match(stderr, new RegExp(`the guide's daily model budget on ${base} is used up \\(GUIDE_BUDGET_SPENT\\)`));
    assert.match(stderr, /docs\/evaluation-adversarial\.md is unchanged/);
  } finally {
    server.close();
  }
});
