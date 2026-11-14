import type { SkipReason, SourceStatus } from '@/api/types';

const FAILING_SKIPS: ReadonlySet<SkipReason> = new Set([
  'RATE_LIMIT_PAUSE',
  'CIRCUIT_OPEN',
  'BUSY',
  'QUOTA_UNAVAILABLE',
]);

type MissReason = Exclude<SourceStatus['outcome'], 'SUCCEEDED'> | SkipReason;

export interface MissedSource {
  sourceId: string;
  name: string;
  reason: MissReason;
  fromCache: boolean;
}

function wasExpectedToAnswer(s: SourceStatus): boolean {
  return (
    s.outcome !== 'SKIPPED' ||
    s.servedFrom !== 'NONE' ||
    (s.skipReason != null && FAILING_SKIPS.has(s.skipReason))
  );
}

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

export function retryCanHelp(missed: readonly MissedSource[]): boolean {
  return missed.some(
    (m) => m.reason === 'FAILED' || m.reason === 'TIMED_OUT' || m.reason === 'BUSY',
  );
}
