import type { GuideResponse } from '@/api/types';

export interface GuidePick {
  rank: number;
  resourceId: string;
  title: string;
  reason: string;
  quote: string;
  reasonCheck: Recommendation['reasonCheck'];
  checks: NonNullable<Recommendation['checks']>;
}

type Recommendation = GuideResponse['recommendations'][number];

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

export function picksById(picks: readonly GuidePick[]): ReadonlyMap<string, GuidePick> {
  return new Map(picks.map((p) => [p.resourceId, p]));
}
