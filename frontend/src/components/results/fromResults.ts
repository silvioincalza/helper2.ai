import type { Location } from 'react-router';
import type { GuidePick } from '@/lib/selection';

/**
 * What a result row passes on to the resource page in the history entry's state (not the URL, not storage): the
 * search to go back to, and for a guide pick its rank, reason, checked quote and requirement checks. The page only
 * reads it back; nothing of it is sent anywhere.
 */
export interface FromResults {
  from?: string | undefined;
  pick?: GuidePick | undefined;
}

export function toFromResults(location: Location, pick: GuidePick | undefined): FromResults {
  return { from: `${location.pathname}${location.search}`, pick };
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

function readPick(value: unknown): GuidePick | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const p = value as Partial<GuidePick>;
  if (typeof p.rank !== 'number' || typeof p.quote !== 'string') return undefined;
  return {
    rank: p.rank,
    resourceId: typeof p.resourceId === 'string' ? p.resourceId : '',
    title: typeof p.title === 'string' ? p.title : '',
    reason: typeof p.reason === 'string' ? p.reason : '',
    quote: p.quote,
    reasonCheck: p.reasonCheck ?? 'NOT_CHECKED',
    checks: Array.isArray(p.checks) ? p.checks : [],
  };
}
