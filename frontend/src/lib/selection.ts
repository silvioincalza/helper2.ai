import type { GuideResponse } from '@/api/types';

/**
 * The selected result of a results page (ADR 0040, 5): the one whose id is in the URL, else the [preferred] one (the
 * guide's pick #1), else the first. An id the user chose always wins. The id only picks among results already loaded,
 * so an unknown or old id never fetches anything.
 */
export function selectedResult<T extends { id: string }>(results: readonly T[], id: string | null, preferred?: string): T | undefined {
  const find = (x: string | null | undefined) => (x ? results.find((r) => r.id === x) : undefined);
  return find(id) ?? find(preferred) ?? results[0];
}

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
      rank: i + 1, resourceId: r.resourceId, title: r.title, reason: r.reason.trim(), quote: r.quote,
      reasonCheck: r.reasonCheck, checks: r.checks ?? [],
    }));
}

/** The picks by resource id, so a row finds its own badge and reason. */
export function picksById(picks: readonly GuidePick[]): ReadonlyMap<string, GuidePick> {
  return new Map(picks.map((p) => [p.resourceId, p]));
}

/**
 * The list of a results page with the guide's picks first (ADR 0040, 6): [picked] holds the picks found among
 * [results], in the guide's order; [rest] the other results, in search order. Each result is in one of the two, once.
 */
export function picksFirst<T extends { id: string }>(results: readonly T[], picks: readonly GuidePick[]): { picked: T[]; rest: T[] } {
  const byId = new Map(results.map((r) => [r.id, r]));
  const picked = picks.map((p) => byId.get(p.resourceId)).filter((r): r is T => r !== undefined);
  const taken = new Set(picked.map((r) => r.id));
  return { picked, rest: results.filter((r) => !taken.has(r.id)) };
}

/** The host of [url] without "www.", for "Open on …" and the row's source line; the url itself when it does not parse. */
export function displayHost(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}
