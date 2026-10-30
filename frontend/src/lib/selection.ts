import type { GuideResponse } from '@/api/types';

/**
 * The selected result of a results page (ADR 0040, 5): the one whose id is in the URL, else the first. The id only
 * picks among results already loaded, so an unknown or old id never fetches anything.
 */
export function selectedResult<T extends { id: string }>(results: readonly T[], id: string | null): T | undefined {
  return (id ? results.find((r) => r.id === id) : undefined) ?? results[0];
}

/** One guide pick as the results page shows it: its place in the answer (1, 2, …), the resource and why. */
export interface GuidePick {
  rank: number;
  resourceId: string;
  title: string;
  reason: string;
  quote: string;
}

/**
 * The guide's picks, in the guide's order, ranked from 1. Only picks the model reasoned are picks: the plain search
 * fallback the guide returns without a model is not shown as a pick.
 */
export function guidePicks(answer: GuideResponse | null): GuidePick[] {
  if (!answer) return [];
  return answer.recommendations
    .filter((r) => r.aiReasoned)
    .map((r, i) => ({ rank: i + 1, resourceId: r.resourceId, title: r.title, reason: r.reason, quote: r.quote }));
}

/** The picks by resource id, so a row finds its own badge and reason. The list order never comes from here. */
export function picksById(picks: readonly GuidePick[]): ReadonlyMap<string, GuidePick> {
  return new Map(picks.map((p) => [p.resourceId, p]));
}

/** The host of [url] without "www.", for "Open on …" and the row's source line; the url itself when it does not parse. */
export function displayHost(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}
