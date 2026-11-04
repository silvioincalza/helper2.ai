import type { ReviewKind, ReviewStatus, ScoutRun } from '@/api/types';
import type { StatusTone } from '@/components/ui/StatusPill';

export const REVIEW_KINDS: readonly ReviewKind[] = [
  'ENRICHMENT_VALIDATION',
  'RELATIONSHIP_CANDIDATE',
  'USER_REPORT',
  'COURSE_CANDIDATE',
];
export const REVIEW_STATUSES: readonly ReviewStatus[] = [
  'OPEN',
  'ACCEPTED',
  'REJECTED',
  'SUPERSEDED',
];
/** Each status in words; the pill's tone only repeats it. */
export const REVIEW_TONE: Record<ReviewStatus, StatusTone> = {
  OPEN: 'next',
  ACCEPTED: 'ok',
  REJECTED: 'warn',
  SUPERSEDED: 'later',
};
export const RUN_TONE: Record<ScoutRun['status'], StatusTone> = {
  RUNNING: 'next',
  SUCCEEDED: 'ok',
  FAILED: 'warn',
  SKIPPED: 'later',
};

/** A value from the address bar, or undefined when it is not one of [allowed]. */
export function oneOf<T extends string>(
  value: string | null,
  allowed: readonly T[],
): T | undefined {
  return allowed.find((a) => a === value);
}
