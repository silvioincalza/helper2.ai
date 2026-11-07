import type { SourceInfo } from '@/api/types';

/**
 * The API fields design v4 asked for (ADR 0028 (part was 0042), decision 13), read in one place: `newThisWeek` on GET /sources,
 * `total` on GET /recent and GET /events/upcoming. Each reader says plainly what the page does when a value is null or
 * not there yet: a dot of the default size, the rows as the count. `limits` on GET /me is read by GuideAnswersLeft.
 */

/**
 * Resources this source brought in the last 7 days, or null when it has no count of its own (`helper2-semantic`, the
 * index searched by meaning): its dot keeps the default size.
 */
export function newThisWeek(source: SourceInfo): number | null {
  return source.newThisWeek ?? null;
}

/**
 * The sources on the Home radar and in its count: enabled and run by someone else (`external`). Helper2's own index,
 * its search by meaning and its curated list are left out.
 */
export function radarSources(sources: SourceInfo[]): SourceInfo[] {
  return sources.filter((s) => s.enabled && s.external);
}

/** How many items the whole list holds: `total` from the API, never fewer than the rows it sent. */
export function listTotal(response: { total: number } | undefined, itemsShown: number): number {
  return Math.max(response?.total ?? 0, itemsShown);
}
