import type {
  AudiencePath,
  EnrichmentQueueEntry,
  EnrichmentQueueView,
  ExampleCheckOutcome,
  ReviewKind,
  ReviewStatus,
  ScoutRun,
} from '@/api/types';
import type { StatusTone } from '@/ui/StatusPill';

export const REVIEW_KINDS: readonly ReviewKind[] = [
  'ENRICHMENT_VALIDATION',
  'RELATIONSHIP_CANDIDATE',
  'USER_REPORT',
  'COURSE_CANDIDATE',
  'EXAMPLE_CANDIDATE',
  'RESOURCE_CANDIDATE',
  'KNOWN_TARGET_CANDIDATE',
];
export const REVIEW_STATUSES: readonly ReviewStatus[] = [
  'OPEN',
  'ACCEPTED',
  'REJECTED',
  'SUPERSEDED',
];
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

export const QUEUE_VIEWS: readonly EnrichmentQueueView[] = ['WAITING', 'SKIPPED', 'OUT_OF_RETRIES'];
export const QUEUE_TONE: Record<
  NonNullable<EnrichmentQueueEntry['waiting']> | EnrichmentQueueEntry['state'],
  StatusTone
> = {
  NEW: 'next',
  MODEL_UNAVAILABLE: 'later',
  RETRY: 'warn',
  PRIORITISED: 'ok',
  WAITING: 'next',
  SKIPPED: 'later',
  OUT_OF_RETRIES: 'warn',
};

export const CHECK_OUTCOMES: readonly ExampleCheckOutcome[] = ['PASS', 'WEAK', 'FAIL', 'PENDING'];
export const CHECK_TONE: Record<ExampleCheckOutcome, StatusTone> = {
  PASS: 'ok',
  WEAK: 'later',
  FAIL: 'warn',
  PENDING: 'next',
};

export const PATHS: readonly AudiencePath[] = ['USE_AI', 'BUILD_WITH_AI'];
export const SHOWN_PER_PATH = 2;

export function oneOf<T extends string>(
  value: string | null,
  allowed: readonly T[],
): T | undefined {
  return allowed.find((a) => a === value);
}
