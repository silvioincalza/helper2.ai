import type { Location } from 'react-router';
import type { GuidePick } from '@/lib/selection';

/** Of a guide pick, only what the resource page shows: its rank, reason, checked quote and requirement checks. */
export type PickForPage = Pick<GuidePick, 'rank' | 'reason' | 'quote' | 'reasonCheck' | 'checks'>;

/**
 * What a result row or a pick chip passes on to the resource page in the history entry's state (not the URL, not
 * storage): the search to go back to and, for a guide pick, only what the page shows of it. The browser keeps it with
 * that entry of the tab's history, so it is gone when the tab is closed; the Privacy page says so. The page only
 * reads it back; nothing of it is sent anywhere.
 */
export interface FromResults {
  from?: string | undefined;
  pick?: PickForPage | undefined;
}

export function toFromResults(location: Location, pick: GuidePick | undefined): FromResults {
  return { from: `${location.pathname}${location.search}`, pick: pick && forPage(pick) };
}

/** Reads the state back. Only a path inside the site, so the back link can never leave it. */
export function fromResults(state: unknown): FromResults {
  if (!state || typeof state !== 'object') return {};
  const s = state as Record<string, unknown>;
  const from =
    typeof s.from === 'string' && s.from.startsWith('/') && !s.from.startsWith('//')
      ? s.from
      : undefined;
  return { from, pick: readPick(s.pick) };
}

function forPage(p: PickForPage): PickForPage {
  return {
    rank: p.rank,
    reason: p.reason,
    quote: p.quote,
    reasonCheck: p.reasonCheck,
    checks: p.checks.map(({ constraint, status, quote }) => ({
      constraint,
      status,
      quote: quote ?? null,
    })),
  };
}

function readPick(value: unknown): PickForPage | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const p = value as Partial<PickForPage>;
  if (typeof p.rank !== 'number' || typeof p.quote !== 'string') return undefined;
  return forPage({
    rank: p.rank,
    reason: typeof p.reason === 'string' ? p.reason : '',
    quote: p.quote,
    reasonCheck: p.reasonCheck ?? 'NOT_CHECKED',
    checks: Array.isArray(p.checks) ? p.checks : [],
  });
}
