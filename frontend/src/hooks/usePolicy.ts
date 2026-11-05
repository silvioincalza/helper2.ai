import { useEffect, useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { request } from '@/api/client';
import type { Policy, WebSearchLimits } from '@/api/types';
import { formatDuration } from '@/lib/duration';

/**
 * The numbers the Mission, Privacy and Terms pages state, from GET /policy (ADR 0042 decision 13, ADR 0051). Read
 * once per session: the values change only when the server restarts. Only the text pages import this module, so the
 * read stays out of the first download.
 */
type PolicyState =
  { status: 'loading' } | { status: 'ready'; policy: Policy } | { status: 'failed' };

/** How long opening a text page waits for the numbers, so it usually opens with them in place. */
export const POLICY_WAIT_MS = 500;

let state: PolicyState = { status: 'loading' };
let inFlight: Promise<void> | undefined;
const listeners = new Set<() => void>();

function set(next: PolicyState) {
  state = next;
  listeners.forEach((l) => l());
}

function load(): Promise<void> {
  if (state.status === 'ready') return Promise.resolve();
  inFlight ??= request<Policy>('GET', '/policy')
    .then(
      (policy) => set({ status: 'ready', policy }),
      () => set({ status: 'failed' }),
    )
    .finally(() => {
      inFlight = undefined;
    });
  return inFlight;
}

/**
 * Starts the read when a text page opens, beside the page's own chunk, and resolves when the numbers are in or after
 * [POLICY_WAIT_MS], whichever comes first. Never rejects. A read that failed on an earlier page is tried again.
 */
export function preloadPolicy(): Promise<void> {
  if (state.status === 'failed') set({ status: 'loading' });
  return Promise.race([load(), new Promise<void>((r) => setTimeout(r, POLICY_WAIT_MS))]);
}

/** Forgets the numbers (between tests). */
export function clearPolicyCache() {
  state = { status: 'loading' };
  inFlight = undefined;
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** The values the locale strings interpolate: `{{maxCandidates}}`, `{{cachedSearches}}` and so on. */
export type PolicyValues = Record<
  | 'maxCandidates'
  | 'maxPicks'
  | 'guideQuestions'
  | 'webSearches'
  | 'cachedSearches'
  | 'cachedGuideAnswers'
  | 'translations'
  | 'queryVectors'
  | 'storedWebResults'
  | 'inactiveAccounts'
  | 'handledReports',
  string
>;

export interface PolicyText {
  /** Undefined while loading and after an error: the pages then show the sentences that need no number. */
  values?: PolicyValues;
  /** The read failed: the page says so once, and states its limits and periods without numbers. */
  failed: boolean;
}

function webSearches(limits: Policy['dailyLimits']['webSearches'], t: TFunction, n: Num): string {
  const perFeature = (l: WebSearchLimits) =>
    t('policy.webSearches', { search: n(l.search), guide: n(l.guide) });
  if (!limits.registered) return perFeature(limits.guest);
  return t('policy.webSearchesByTier', {
    guest: perFeature(limits.guest),
    registered: perFeature(limits.registered),
  });
}

type Num = (n: number) => string;

/** The policy as the words and numbers the pages show. */
export function policyValues(p: Policy, t: TFunction, lang: string): PolicyValues {
  const fmt = new Intl.NumberFormat(lang);
  const n: Num = (v) => fmt.format(v);
  const d = (s: number) => formatDuration(s, t);
  const r = p.retention;
  return {
    maxCandidates: n(p.guide.maxCandidates),
    maxPicks: n(p.guide.maxPicks),
    guideQuestions: n(p.dailyLimits.guideQuestions),
    webSearches: webSearches(p.dailyLimits.webSearches, t, n),
    cachedSearches: d(r.cachedSearchesSeconds),
    cachedGuideAnswers: d(r.cachedGuideAnswersSeconds),
    translations: d(r.translationsSeconds),
    queryVectors: d(r.queryVectorsSeconds),
    storedWebResults: d(r.storedWebResultsSeconds),
    inactiveAccounts: d(r.inactiveAccountsSeconds),
    handledReports: d(r.handledReportsSeconds),
  };
}

/** The policy numbers for a text page. Reads them when no page has started the read yet. */
export function usePolicyText(): PolicyText {
  const { t, i18n } = useTranslation();
  const current = useSyncExternalStore(subscribe, () => state);
  useEffect(() => {
    if (current.status === 'loading') void load();
  }, [current.status]);
  if (current.status === 'ready')
    return { values: policyValues(current.policy, t, i18n.language), failed: false };
  return { failed: current.status === 'failed' };
}
