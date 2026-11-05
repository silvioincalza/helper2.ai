import type { SkipReason, SourceStatus } from '@/api/types';

/**
 * Skip reasons that mean the source is failing, not left out on purpose. The same set as the backend's
 * `wasExpectedToAnswer`: such a source counts in coverage as queried.
 */
const FAILING_SKIPS: ReadonlySet<SkipReason> = new Set([
  'RATE_LIMIT_PAUSE',
  'CIRCUIT_OPEN',
  'BUSY',
  'QUOTA_UNAVAILABLE',
]);

/** Why a source did not answer, as one key: its outcome, or its skip reason when it was skipped and one is given. */
type MissReason = Exclude<SourceStatus['outcome'], 'SUCCEEDED'> | SkipReason;

export interface MissedSource {
  sourceId: string;
  name: string;
  reason: MissReason;
  /** Saved results from this source were shown instead. */
  fromCache: boolean;
}

/** Whether coverage counts [s] as queried: called, served from cache, or skipped because it is failing. */
function wasExpectedToAnswer(s: SourceStatus): boolean {
  return (
    s.outcome !== 'SKIPPED' ||
    s.servedFrom !== 'NONE' ||
    (s.skipReason != null && FAILING_SKIPS.has(s.skipReason))
  );
}

/** The sources a search counted as queried that did not answer live, in the order of the response. */
export function missedSources(sources: readonly SourceStatus[]): MissedSource[] {
  return sources.flatMap((s): MissedSource[] => {
    if (s.outcome === 'SUCCEEDED' || !wasExpectedToAnswer(s)) return [];
    return [
      {
        sourceId: s.sourceId,
        name: s.name,
        reason: s.outcome === 'SKIPPED' ? (s.skipReason ?? 'SKIPPED') : s.outcome,
        fromCache: s.servedFrom === 'CACHE' || s.servedFrom === 'STALE_CACHE',
      },
    ];
  });
}

/**
 * Whether searching again now can bring a missed source back: an error, a timeout or a full queue can pass in seconds.
 * A rate limit, a pause after a rate limit, an open circuit or an unreadable quota cannot: the source is not called.
 */
export function retryCanHelp(missed: readonly MissedSource[]): boolean {
  return missed.some(
    (m) => m.reason === 'FAILED' || m.reason === 'TIMED_OUT' || m.reason === 'BUSY',
  );
}
