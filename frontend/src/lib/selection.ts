import type { GuideResponse } from '@/api/types';

/** One guide pick as the results page shows it: its place in the answer (1, 2, …), the resource, why, and the proof. */
export interface GuidePick {
  rank: number;
  resourceId: string;
  title: string;
  /** AI-written; empty when the second model call removed it ([reasonCheck] REMOVED). */
  reason: string;
  quote: string;
  reasonCheck: Recommendation['reasonCheck'];
  checks: NonNullable<Recommendation['checks']>;
}

type Recommendation = GuideResponse['recommendations'][number];

/**
 * The guide's picks, in the guide's order, ranked from 1. Only picks the model reasoned are picks: the plain search
 * fallback the guide returns without a model is not shown as a pick.
 */
export function guidePicks(answer: GuideResponse | null): GuidePick[] {
  if (!answer) return [];
  return answer.recommendations
    .filter((r) => r.aiReasoned)
    .map((r, i) => ({
      rank: i + 1,
      resourceId: r.resourceId,
      title: r.title,
      reason: r.reason.trim(),
      quote: r.quote,
      reasonCheck: r.reasonCheck,
      checks: r.checks ?? [],
    }));
}

/** The picks by resource id, so a row finds its own badge and reason. */
export function picksById(picks: readonly GuidePick[]): ReadonlyMap<string, GuidePick> {
  return new Map(picks.map((p) => [p.resourceId, p]));
}
