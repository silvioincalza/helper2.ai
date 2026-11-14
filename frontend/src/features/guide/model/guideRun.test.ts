import type { GuideResponse, GuideStep } from '@/api/types';
import {
  attemptOf,
  guideRun,
  keepsAnswer,
  outlived,
  runIdOf,
  runOf,
  startGuideRun,
  type GuideRunEvent,
  type GuideRunState,
} from './guideRun';

const answer = (status: GuideResponse['status'], notices: GuideResponse['notices'] = []) =>
  ({ status, notices }) as unknown as GuideResponse;

const notice = (code: string) => ({ code, message: code }) as GuideResponse['notices'][number];

const step = (name: string) => ({ step: name }) as unknown as GuideStep;

const A = runIdOf('question A', 0);
const B = runIdOf('question B', 0);

const after = (state: GuideRunState, ...events: GuideRunEvent[]) => events.reduce(guideRun, state);

const askedA = () => startGuideRun('question A', undefined);

const CLEAN = { steps: [], answer: null, error: null, done: false, fromCache: false };

describe('the guide run', () => {
  it('starts clean for a question with no answer in the cache', () => {
    expect(askedA()).toEqual({
      run: { id: A, ...CLEAN },
      kept: null,
      retries: { request: 'question A', n: 0 },
    });
  });

  it('starts finished for a question whose answer is in the cache', () => {
    const cached = answer('OK');

    expect(startGuideRun('question A', cached).run).toEqual({
      ...CLEAN,
      id: A,
      answer: cached,
      done: true,
      fromCache: true,
    });
  });

  it('names a run by its attempt and its request', () => {
    expect(runIdOf('question A', 2)).toBe('2:question A');
  });

  it('a new question starts a clean run and forgets the steps of the one before', () => {
    const state = after(
      askedA(),
      { type: 'step', id: A, step: step('read') },
      { type: 'asked', id: B },
    );

    expect(state.run).toEqual({ id: B, ...CLEAN });
  });

  it('a cache hit is a finished run with its answer and no steps', () => {
    const cached = answer('OK');

    const state = after(askedA(), { type: 'cacheHit', id: B, answer: cached });

    expect(state.run).toEqual({ ...CLEAN, id: B, answer: cached, done: true, fromCache: true });
  });

  it('each step is added after the ones before it', () => {
    const state = after(
      askedA(),
      { type: 'step', id: A, step: step('read') },
      { type: 'step', id: A, step: step('found') },
    );

    expect(state.run).toMatchObject({ id: A, steps: [step('read'), step('found')], done: false });
  });

  it('a step of a run that is not the one held starts that run from a clean state', () => {
    const state = after(
      askedA(),
      { type: 'step', id: A, step: step('read') },
      { type: 'step', id: B, step: step('found') },
    );

    expect(state.run).toEqual({ ...CLEAN, id: B, steps: [step('found')] });
  });

  it('an answer finishes the run and keeps its steps', () => {
    const reply = answer('OK');

    const state = after(
      askedA(),
      { type: 'step', id: A, step: step('read') },
      { type: 'answered', id: A, answer: reply },
    );

    expect(state.run).toEqual({
      ...CLEAN,
      id: A,
      steps: [step('read')],
      answer: reply,
      done: true,
    });
  });

  it('a failure finishes the run with its error and no answer', () => {
    const error = new Error('stream closed');

    const state = after(askedA(), { type: 'failed', id: A, error });

    expect(state.run).toEqual({ ...CLEAN, id: A, error, done: true });
  });

  it('a run that is left is held no more', () => {
    const state = after(
      askedA(),
      { type: 'step', id: A, step: step('read') },
      { type: 'left', id: A },
    );

    expect(state.run).toEqual({ id: '', ...CLEAN });
  });

  it('leaving a run that is not the one held changes nothing', () => {
    const state = after(askedA(), { type: 'asked', id: B });

    expect(guideRun(state, { type: 'left', id: A })).toBe(state);
  });

  it('going back to a question that never finished asks it again from a clean state', () => {
    const state = after(
      askedA(),
      { type: 'step', id: A, step: step('read') },
      { type: 'left', id: A },
      { type: 'asked', id: B },
      { type: 'left', id: B },
    );

    expect(state.run.id).not.toBe(A);
    expect(runOf(state, A)).toEqual({ id: A, ...CLEAN });
    expect(after(state, { type: 'asked', id: A }).run).toEqual({ id: A, ...CLEAN });
  });

  it('going back to an answered question is a cache hit, finished at once', () => {
    const reply = answer('OK');

    const state = after(
      askedA(),
      { type: 'answered', id: A, answer: reply },
      { type: 'left', id: A },
      { type: 'asked', id: B },
      { type: 'left', id: B },
      { type: 'cacheHit', id: A, answer: reply },
    );

    expect(runOf(state, A)).toMatchObject({ answer: reply, error: null, done: true, steps: [] });
  });

  it('the run of an id that is not the one held reads as clean', () => {
    const state = after(askedA(), { type: 'answered', id: A, answer: answer('OK') });

    expect(runOf(state, B)).toEqual({ id: B, ...CLEAN });
    expect(runOf(state, A)).toBe(state.run);
  });

  it('a retry is the next attempt of the same request', () => {
    const once = after(askedA(), { type: 'retried', request: 'question A' });
    const twice = after(once, { type: 'retried', request: 'question A' });

    expect(attemptOf(askedA(), 'question A')).toBe(0);
    expect(attemptOf(once, 'question A')).toBe(1);
    expect(attemptOf(twice, 'question A')).toBe(2);
  });

  it('a retry leaves the run as it is until the next attempt is asked', () => {
    const failed = after(askedA(), { type: 'failed', id: A, error: new Error('no') });

    const retried = after(failed, { type: 'retried', request: 'question A' });

    expect(retried.run).toBe(failed.run);
    const next = runIdOf('question A', attemptOf(retried, 'question A'));
    expect(after(retried, { type: 'asked', id: next }).run).toEqual({
      id: '1:question A',
      ...CLEAN,
    });
  });

  it('the retries of one request do not count for another', () => {
    const retriedA = after(
      askedA(),
      { type: 'retried', request: 'question A' },
      { type: 'retried', request: 'question A' },
    );

    expect(attemptOf(retriedA, 'question B')).toBe(0);
    const retriedB = after(retriedA, { type: 'retried', request: 'question B' });
    expect(attemptOf(retriedB, 'question B')).toBe(1);
    expect(attemptOf(retriedB, 'question A')).toBe(0);
  });

  it('asking again keeps the answer on screen with the run and the question it is from', () => {
    const reply = answer('OK');

    const state = after(
      askedA(),
      { type: 'answered', id: A, answer: reply },
      { type: 'askedAgain', id: A, ask: 'ask A' },
    );

    expect(state.kept).toEqual({ from: A, ask: 'ask A', answer: reply });
    expect(state.run.answer).toBe(reply);
  });

  it('asking again with no answer on screen keeps nothing', () => {
    const state = askedA();

    expect(guideRun(state, { type: 'askedAgain', id: A, ask: 'ask A' })).toBe(state);
    expect(guideRun(state, { type: 'askedAgain', id: B, ask: 'ask B' })).toBe(state);
  });

  it('a kept answer stays while the run that replaces it has not started', () => {
    const state = after(
      askedA(),
      { type: 'answered', id: A, answer: answer('OK') },
      { type: 'askedAgain', id: A, ask: 'ask A' },
      { type: 'asked', id: B },
    );

    expect(outlived(state.kept!, 'ask A', runOf(state, A))).toBe(false);
    expect(outlived(state.kept!, 'ask A', runOf(state, B))).toBe(false);
  });

  it('a kept answer is outlived by the first step or the end of the run that replaces it', () => {
    const kept = after(
      askedA(),
      { type: 'answered', id: A, answer: answer('OK') },
      { type: 'askedAgain', id: A, ask: 'ask A' },
      { type: 'asked', id: B },
    );
    const stepped = after(kept, { type: 'step', id: B, step: step('read') });
    const failed = after(kept, { type: 'failed', id: B, error: new Error('no') });

    expect(outlived(stepped.kept!, 'ask A', runOf(stepped, B))).toBe(true);
    expect(outlived(failed.kept!, 'ask A', runOf(failed, B))).toBe(true);
  });

  it('a kept answer is outlived by another question', () => {
    const state = after(
      askedA(),
      { type: 'answered', id: A, answer: answer('OK') },
      { type: 'askedAgain', id: A, ask: 'ask A' },
    );

    expect(outlived(state.kept!, 'ask B', runOf(state, A))).toBe(true);
  });

  it('an outlived kept answer is dropped and the run stays', () => {
    const kept = after(
      askedA(),
      { type: 'answered', id: A, answer: answer('OK') },
      { type: 'askedAgain', id: A, ask: 'ask A' },
    );

    const state = guideRun(kept, { type: 'keptOutlived' });

    expect(state.kept).toBeNull();
    expect(state.run).toBe(kept.run);
  });

  it('no event changes the state it is given', () => {
    const state = after(askedA(), { type: 'step', id: A, step: step('read') });
    const before = structuredClone(state);

    after(
      state,
      { type: 'step', id: A, step: step('found') },
      { type: 'answered', id: A, answer: answer('OK') },
      { type: 'askedAgain', id: A, ask: 'ask A' },
      { type: 'retried', request: 'question A' },
      { type: 'left', id: A },
    );

    expect(state).toEqual(before);
  });

  it.each([
    ['an answer', answer('OK'), true],
    ['no candidates', answer('NO_CANDIDATES'), true],
    ['a question back', answer('NEEDS_CLARIFICATION'), true],
    ['the daily quota used up', answer('LLM_UNAVAILABLE', [notice('GUIDE_DAILY_QUOTA')]), true],
    ['a partial answer', answer('PARTIAL'), false],
    ['a provider failure', answer('LLM_UNAVAILABLE'), false],
    [
      "the guide's model budget used up",
      answer('LLM_UNAVAILABLE', [notice('GUIDE_BUDGET_SPENT')]),
      false,
    ],
  ])('%s is kept for a way back only when asking again cannot change it', (_, reply, kept) => {
    expect(keepsAnswer(reply)).toBe(kept);
  });
});
