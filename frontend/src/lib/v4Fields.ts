import type { SourceInfo } from '@/api/types';
import type { Session } from '@/context/MeContext';

/**
 * The API fields design v4 asked for (ADR 0042, decision 7), read in one place: `newThisWeek` on GET /sources,
 * `total` on GET /recent and GET /events/upcoming, `limits` on GET /me. Each reader says plainly what the page does
 * when a value is null or not there yet: a dot of the default size, the rows as the count, no quota line.
 */

/** Today's AI answers (the guide) for this visitor: how many are left of the daily limit, and when they come back. */
export interface AiAnswers {
  remaining: number;
  limit: number;
  resetsAt: string;
}

/**
 * Resources this source brought in the last 7 days, or null when it has no count of its own (`helper2-semantic`, the
 * index searched by meaning): its dot keeps the default size.
 */
export function newThisWeek(source: SourceInfo): number | null {
  return source.newThisWeek ?? null;
}

/** How many items the whole list holds: `total` from the API, never fewer than the rows it sent. */
export function listTotal(response: { total: number } | undefined, itemsShown: number): number {
  return Math.max(response?.total ?? 0, itemsShown);
}

/**
 * The visitor's AI answers today (`limits.guideQuestions` on GET /me), or null: before GET /me answers, and when the
 * guide has no model (it then answers from search only and spends nothing), so no line is shown.
 */
export function aiAnswers(me: Session): AiAnswers | null {
  const allowance = me.limits?.guideQuestions;
  if (!me.limits || !allowance || allowance.limit === 0) return null;
  return {
    remaining: Math.min(allowance.remaining, allowance.limit),
    limit: allowance.limit,
    resetsAt: me.limits.resetsAt,
  };
}
