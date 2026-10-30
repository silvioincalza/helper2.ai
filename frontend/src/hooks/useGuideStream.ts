import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api } from '@/api/client';
import type { GuideRequest, GuideResponse, GuideStep } from '@/api/types';
import { askedQuestion, askedRefinement, refinedParams, type Refinement } from '@/lib/searchState';

export interface GuideStream {
  /** The question in the URL (`ask`), '' when there is none. */
  question: string;
  /** Changes with every request, a retry included: views that count time key on it. */
  id: string;
  steps: GuideStep[];
  answer: GuideResponse | null;
  /** Why the last request failed (an ApiError or a transport error); null while it runs or after an answer. */
  error: unknown;
  busy: boolean;
  /** Asks the same question again with [r] added to the refinement in the URL. */
  refine: (r: Partial<Refinement>) => void;
  /** Asks the same question with the same refinement again. */
  retry: () => void;
}

interface Run {
  id: string;
  steps: GuideStep[];
  answer: GuideResponse | null;
  error: unknown;
  done: boolean;
}

const fresh = (id: string): Run => ({ id, steps: [], answer: null, error: null, done: false });

function guideRequest(question: string, { clarification, drop }: Refinement): GuideRequest {
  const body: GuideRequest = { request: question };
  if (clarification !== undefined && clarification !== null) body.clarification = clarification;
  if (drop.length) body.drop = drop;
  return body;
}

/**
 * The guide's answer stream for the question and refinement in the URL (`ask`, `clar`, `drop`), so a retry and a
 * reload ask the same thing. One request per distinct question, refinement and attempt; a change, or unmounting,
 * aborts the running one. The backend shares identical in-flight questions, so replacing one costs nothing extra.
 */
export function useGuideStream(): GuideStream {
  const [sp, setSp] = useSearchParams();
  const question = askedQuestion(sp);
  const [attempt, setAttempt] = useState(0);
  // The request as text: the effect runs once per distinct request, not once per render.
  const request = question ? JSON.stringify(guideRequest(question, askedRefinement(sp))) : '';
  const id = `${attempt}:${request}`;
  const [run, setRun] = useState<Run>(() => fresh(id));

  useEffect(() => {
    if (!request) return;
    const ctrl = new AbortController();
    const runId = `${attempt}:${request}`;
    const update = (change: (r: Run) => Run) => {
      if (!ctrl.signal.aborted) setRun((r) => change(r.id === runId ? r : fresh(runId)));
    };
    api.askGuideStream(JSON.parse(request) as GuideRequest, (step) => update((r) => ({ ...r, steps: [...r.steps, step] })), ctrl.signal)
      .then((answer) => update((r) => ({ ...r, answer, done: true })))
      .catch((error: unknown) => update((r) => ({ ...r, error, done: true })));
    return () => ctrl.abort();
  }, [request, attempt]);

  // Until the new request reports anything, the last one's steps and answer are not shown.
  const current = run.id === id ? run : fresh(id);
  return {
    question,
    id,
    steps: current.steps,
    answer: current.answer,
    error: current.error,
    busy: Boolean(question) && !current.done,
    refine: (r) => setSp((prev) => refinedParams(prev, r), { preventScrollReset: true }),
    retry: () => setAttempt((n) => n + 1),
  };
}
