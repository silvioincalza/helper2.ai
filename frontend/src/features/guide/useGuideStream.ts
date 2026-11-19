import { useEffect, useReducer, useState } from 'react';
import { useLocation, useSearchParams } from 'react-router';
import { api } from '@/api/client';
import { useMe } from '@/context/MeContext';
import type { AudiencePath, GuideRequest, GuideResponse, GuideStep } from '@/api/types';
import {
  attemptOf,
  guideRun,
  keepsAnswer,
  outlived,
  runIdOf,
  runOf,
  startGuideRun,
  type GuideRunEvent,
} from '@/features/guide/model/guideRun';
import { recentAnswer, remember } from '@/hooks/useApi';
import {
  askedQuestion,
  askedNow,
  askedRefinement,
  askToken,
  parseSearchParams,
  refinedParams,
  type Refinement,
} from '@/lib/searchState';

export interface GuideStream {
  steps: GuideStep[];
  answer: GuideResponse | null;
  error: unknown;
  busy: boolean;
  previous: GuideResponse | null;
  refine: (r: Partial<Refinement>) => void;
  retry: () => void;
  filtersChanged: boolean;
  askAgain: () => void;
}

interface AskedFilters {
  ask: string;
  token: number | undefined;
  categories: string[];
  path: AudiencePath | '';
}

const cacheKey = (request: string) => `guide:${request}`;

const ASKED_MAX = 50;
const askedOnPage = new Map<string, AskedFilters>();

function rememberAsked(search: string, filters: AskedFilters) {
  askedOnPage.delete(search);
  askedOnPage.set(search, filters);
  while (askedOnPage.size > ASKED_MAX) {
    const oldest = askedOnPage.keys().next().value;
    if (oldest === undefined) break;
    askedOnPage.delete(oldest);
  }
}

function askedFilters(ask: string, token: number | undefined, sp: URLSearchParams): AskedFilters {
  const seen = askedOnPage.get(sp.toString());
  return seen && stillAsked(seen, ask, token) ? seen : filtersAt(ask, token, sp);
}

function stillAsked(asked: AskedFilters, ask: string, token: number | undefined): boolean {
  return asked.ask === ask && (token === undefined || token === asked.token);
}

function cachedAnswer(request: string, attempt: number): GuideResponse | undefined {
  return request && attempt === 0 ? recentAnswer<GuideResponse>(cacheKey(request)) : undefined;
}

function startOf(id: string, cached: GuideResponse | undefined): GuideRunEvent {
  return cached ? { type: 'cacheHit', id, answer: cached } : { type: 'asked', id };
}

function guideRequest(
  question: string,
  { clarification, drop }: Refinement,
  { categories, path }: AskedFilters,
): GuideRequest {
  const body: GuideRequest = { request: question };
  if (path) body.path = path;
  if (clarification !== undefined && clarification !== null) body.clarification = clarification;
  if (drop.length) body.drop = drop;
  if (categories.length) body.categories = categories;
  return body;
}

function sameFilters(a: AskedFilters, b: AskedFilters): boolean {
  const sorted = (list: string[]) => [...list].sort().join('\n');
  return a.path === b.path && sorted(a.categories) === sorted(b.categories);
}

function filtersAt(ask: string, token: number | undefined, sp: URLSearchParams): AskedFilters {
  const state = parseSearchParams(sp);
  return { ask, token, categories: state.filters.category, path: state.path };
}

export function useGuideStream(): GuideStream {
  const [sp, setSp] = useSearchParams();
  const question = askedQuestion(sp);
  const refinement = askedRefinement(sp);
  const ask = question ? JSON.stringify([question, refinement]) : '';
  const token = askToken(useLocation().state);
  const [asked, setAsked] = useState<AskedFilters>(() => askedFilters(ask, token, sp));
  const filters = stillAsked(asked, ask, token) ? asked : filtersAt(ask, token, sp);
  if (filters !== asked) setAsked(filters);
  const request = question ? JSON.stringify(guideRequest(question, refinement, filters)) : '';
  const [state, tell] = useReducer(guideRun, request, (first) =>
    startGuideRun(first, cachedAnswer(first, 0)),
  );
  const attempt = attemptOf(state, request);
  const id = runIdOf(request, attempt);
  if (state.run.id !== id) tell(startOf(id, cachedAnswer(request, attempt)));
  const current = runOf(state, id);
  const cached = current.fromCache;
  const { refresh: refreshLimits } = useMe();
  const search = sp.toString();

  useEffect(() => rememberAsked(search, filters), [search, filters]);

  useEffect(() => {
    if (!request || cached) return;
    const runId = runIdOf(request, attempt);
    const ctrl = new AbortController();
    const tellUnlessLeft = (event: GuideRunEvent) => {
      if (!ctrl.signal.aborted) tell(event);
    };
    api
      .askGuideStream(
        JSON.parse(request) as GuideRequest,
        (step) => tellUnlessLeft({ type: 'step', id: runId, step }),
        ctrl.signal,
      )
      .then((answer) => {
        if (!ctrl.signal.aborted && keepsAnswer(answer)) remember(cacheKey(request), answer);
        tellUnlessLeft({ type: 'answered', id: runId, answer });
      })
      .catch((error: unknown) => tellUnlessLeft({ type: 'failed', id: runId, error }))
      .finally(() => {
        if (!ctrl.signal.aborted) refreshLimits();
      });
    return () => {
      ctrl.abort();
      tell({ type: 'left', id: runId });
    };
  }, [request, attempt, cached, refreshLimits]);

  const busy = Boolean(question) && !current.done;
  const { kept } = state;
  if (kept && outlived(kept, ask, current)) tell({ type: 'keptOutlived' });
  const previous =
    kept && kept.ask === ask && current.id !== kept.from && busy ? kept.answer : null;
  const askAgain = () => {
    tell({ type: 'askedAgain', id, ask });
    setSp((prev) => prev, { preventScrollReset: true, state: askedNow() });
  };
  const filtersChanged =
    current.answer !== null && !busy && !sameFilters(filters, filtersAt(ask, token, sp));
  return {
    filtersChanged,
    askAgain,
    previous,
    steps: current.steps,
    answer: current.answer,
    error: current.error,
    busy,
    refine: (r) => setSp((prev) => refinedParams(prev, r), { preventScrollReset: true }),
    retry: () => tell({ type: 'retried', request }),
  };
}
