import { useEffect, useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { request } from '@/api/client';
import type { Policy, WebSearchLimits } from '@/api/types';
import { formatDuration } from '@/lib/duration';

type PolicyState =
  { status: 'loading' } | { status: 'ready'; policy: Policy } | { status: 'failed' };

const POLICY_WAIT_MS = 500;

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

export function preloadPolicy(): Promise<void> {
  if (state.status === 'failed') set({ status: 'loading' });
  return Promise.race([load(), new Promise<void>((r) => setTimeout(r, POLICY_WAIT_MS))]);
}

export function clearPolicyCache() {
  state = { status: 'loading' };
  inFlight = undefined;
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export type PolicyValues = Record<
  | 'maxCandidates'
  | 'maxPicks'
  | 'guideQuestions'
  | 'freeAnswers'
  | 'webSearches'
  | 'cachedSearches'
  | 'cachedGuideAnswers'
  | 'translations'
  | 'queryVectors'
  | 'storedWebResults'
  | 'webSearchCounters'
  | 'guideQuestionCounters'
  | 'webNews'
  | 'pendingReviewPages'
  | 'listIndex'
  | 'pageText'
  | 'pageRead'
  | 'backups'
  | 'inactiveAccounts'
  | 'handledReports'
  | 'adminRecords'
  | 'runtimeSettingChanges'
  | 'categories'
  | 'categorySearches'
  | 'quoteChars'
  | 'articleExcerptChars'
  | 'productPageExcerptChars'
  | 'productPages'
  | 'errorLogChars',
  string
>;

export interface PolicyText {
  values?: PolicyValues;
  failed: boolean;
}

function webSearches(limits: WebSearchLimits, t: TFunction, n: Num): string {
  return t('policy.webSearches', { search: n(limits.search), guide: n(limits.guide) });
}

type Num = (n: number) => string;

export function policyValues(p: Policy, t: TFunction, lang: string): PolicyValues {
  const fmt = new Intl.NumberFormat(lang);
  const n: Num = (v) => fmt.format(v);
  const d = (s: number) => formatDuration(s, t);
  const r = p.retention;
  const s = p.sources;
  return {
    maxCandidates: n(p.guide.maxCandidates),
    maxPicks: n(p.guide.maxPicks),
    guideQuestions: n(p.dailyLimits.guideQuestions),
    freeAnswers: n(p.dailyLimits.freeAnswers),
    webSearches: webSearches(p.dailyLimits.webSearches, t, n),
    cachedSearches: d(r.cachedSearchesSeconds),
    cachedGuideAnswers: d(r.cachedGuideAnswersSeconds),
    translations: d(r.translationsSeconds),
    queryVectors: d(r.queryVectorsSeconds),
    storedWebResults: d(r.storedWebResultsSeconds),
    webSearchCounters: d(r.webSearchCountersSeconds),
    guideQuestionCounters: d(r.guideQuestionCountersSeconds),
    webNews: d(r.webNewsSeconds),
    pendingReviewPages: d(r.pendingReviewPagesSeconds),
    listIndex: d(r.listIndexSeconds),
    pageText: d(r.pageTextSeconds),
    pageRead: d(r.pageReadSeconds),
    backups: d(r.backupsSeconds),
    inactiveAccounts: d(r.inactiveAccountsSeconds),
    handledReports: d(r.handledReportsSeconds),
    adminRecords: d(r.adminRecordsSeconds),
    runtimeSettingChanges: d(r.runtimeSettingChangesSeconds),
    categories: n(s.categories),
    categorySearches: n(s.categorySearches),
    quoteChars: n(s.quoteChars),
    articleExcerptChars: n(s.articleExcerptChars),
    productPageExcerptChars: n(s.productPageExcerptChars),
    productPages: n(s.productPages),
    errorLogChars: n(s.errorLogChars),
  };
}

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
