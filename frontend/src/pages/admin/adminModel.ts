import type {
  AudiencePath,
  ExampleCheckOutcome,
  ReviewKind,
  ReviewStatus,
  ScoutRun,
} from '@/api/types';
import type { StatusTone } from '@/components/ui/StatusPill';

export const REVIEW_KINDS: readonly ReviewKind[] = [
  'ENRICHMENT_VALIDATION',
  'RELATIONSHIP_CANDIDATE',
  'USER_REPORT',
  'COURSE_CANDIDATE',
  'EXAMPLE_CANDIDATE',
  'RESOURCE_CANDIDATE',
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

export const CHECK_OUTCOMES: readonly ExampleCheckOutcome[] = ['PASS', 'WEAK', 'FAIL', 'PENDING'];
/** Shown in words first; the tone only repeats it. */
export const CHECK_TONE: Record<ExampleCheckOutcome, StatusTone> = {
  PASS: 'ok',
  WEAK: 'later',
  FAIL: 'warn',
  PENDING: 'next',
};

export const PATHS: readonly AudiencePath[] = ['USE_AI', 'BUILD_WITH_AI'];
/** The home page shows this many per path (frontend/src/lib/examples.ts); fewer shown is a warning. */
export const SHOWN_PER_PATH = 2;

/** A value from the address bar, or undefined when it is not one of [allowed]. */
export function oneOf<T extends string>(
  value: string | null,
  allowed: readonly T[],
): T | undefined {
  return allowed.find((a) => a === value);
}
