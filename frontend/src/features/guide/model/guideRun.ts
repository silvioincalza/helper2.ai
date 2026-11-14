import type { GuideResponse, GuideStep } from '@/api/types';

interface Run {
  id: string;
  steps: GuideStep[];
  answer: GuideResponse | null;
  error: unknown;
  done: boolean;
  fromCache: boolean;
}

interface KeptAnswer {
  from: string;
  ask: string;
  answer: GuideResponse;
}

interface Retries {
  request: string;
  n: number;
}

export interface GuideRunState {
  run: Run;
  kept: KeptAnswer | null;
  retries: Retries;
}

export type GuideRunEvent =
  | { type: 'asked'; id: string }
  | { type: 'cacheHit'; id: string; answer: GuideResponse }
  | { type: 'step'; id: string; step: GuideStep }
  | { type: 'answered'; id: string; answer: GuideResponse }
  | { type: 'failed'; id: string; error: unknown }
  | { type: 'left'; id: string }
  | { type: 'retried'; request: string }
  | { type: 'askedAgain'; id: string; ask: string }
  | { type: 'keptOutlived' };

const NO_RUN = '';

const fresh = (id: string): Run => ({
  id,
  steps: [],
  answer: null,
  error: null,
  done: false,
  fromCache: false,
});

const fromCache = (id: string, answer: GuideResponse): Run => ({
  ...fresh(id),
  answer,
  done: true,
  fromCache: true,
});

const startRun = (id: string, cached: GuideResponse | undefined): Run =>
  cached ? fromCache(id, cached) : fresh(id);

const started = (run: Run) => run.done || run.steps.length > 0;

export function outlived(kept: KeptAnswer, ask: string, run: Run): boolean {
  return kept.ask !== ask || (run.id !== kept.from && started(run));
}

const KEPT_STATUSES: ReadonlySet<GuideResponse['status']> = new Set([
  'OK',
  'NO_CANDIDATES',
  'NEEDS_CLARIFICATION',
]);

export function keepsAnswer(answer: GuideResponse): boolean {
  if (KEPT_STATUSES.has(answer.status)) return true;
  return (
    answer.status === 'LLM_UNAVAILABLE' &&
    answer.notices.some((n) => n.code === 'GUIDE_DAILY_QUOTA')
  );
}

export const runIdOf = (request: string, attempt: number) => `${attempt}:${request}`;

export const attemptOf = ({ retries }: GuideRunState, request: string) =>
  retries.request === request ? retries.n : 0;

export const runOf = ({ run }: GuideRunState, id: string): Run => (run.id === id ? run : fresh(id));

export const startGuideRun = (
  request: string,
  cached: GuideResponse | undefined,
): GuideRunState => ({
  run: startRun(runIdOf(request, 0), cached),
  kept: null,
  retries: { request, n: 0 },
});

function keepAnswerOf(state: GuideRunState, id: string, ask: string): GuideRunState {
  const { answer } = runOf(state, id);
  return answer ? { ...state, kept: { from: id, ask, answer } } : state;
}

export function guideRun(state: GuideRunState, event: GuideRunEvent): GuideRunState {
  switch (event.type) {
    case 'asked':
      return { ...state, run: fresh(event.id) };
    case 'cacheHit':
      return { ...state, run: fromCache(event.id, event.answer) };
    case 'step': {
      const run = runOf(state, event.id);
      return { ...state, run: { ...run, steps: [...run.steps, event.step] } };
    }
    case 'answered':
      return { ...state, run: { ...runOf(state, event.id), answer: event.answer, done: true } };
    case 'failed':
      return { ...state, run: { ...runOf(state, event.id), error: event.error, done: true } };
    case 'left':
      return state.run.id === event.id ? { ...state, run: fresh(NO_RUN) } : state;
    case 'retried':
      return {
        ...state,
        retries: { request: event.request, n: attemptOf(state, event.request) + 1 },
      };
    case 'askedAgain':
      return keepAnswerOf(state, event.id, event.ask);
    case 'keptOutlived':
      return { ...state, kept: null };
  }
}
