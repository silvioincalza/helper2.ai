import { useLocation, type Location } from 'react-router';
import { resourceTypeValues } from '@/api/schema';
import type { ResourceType, ResultCard } from '@/api/types';
import { safeHref } from '@/lib/format';
import type { GuidePick } from '@/lib/selection';

export type PickForPage = Pick<GuidePick, 'rank' | 'reason' | 'quote' | 'reasonCheck' | 'checks'>;

export type CardHead = Pick<ResultCard, 'id' | 'title' | 'url' | 'type'>;

export interface FromResults {
  from?: string | undefined;
  pick?: PickForPage | undefined;
  card?: CardHead | undefined;
}

export function toFromResults(
  location: Location,
  pick: GuidePick | undefined,
  card?: CardHead,
): FromResults {
  return {
    from: `${location.pathname}${location.search}`,
    pick: pick && forPage(pick),
    card: card && { id: card.id, title: card.title, url: card.url, type: card.type },
  };
}

export function useFromHere(): FromResults {
  return toFromResults(useLocation(), undefined);
}

const SAME_SITE_PATH = /^\/(?![/\\])/;

export function fromResults(state: unknown): FromResults {
  if (!state || typeof state !== 'object') return {};
  const s = state as Record<string, unknown>;
  const from = typeof s.from === 'string' && SAME_SITE_PATH.test(s.from) ? s.from : undefined;
  return { from, pick: readPick(s.pick), card: readCard(s.card) };
}

function readCard(value: unknown): CardHead | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const c = value as Record<string, unknown>;
  if (typeof c.id !== 'string' || typeof c.title !== 'string' || typeof c.url !== 'string')
    return undefined;
  if (!safeHref(c.url) || !resourceTypeValues.includes(c.type as ResourceType)) return undefined;
  return { id: c.id, title: c.title, url: c.url, type: c.type as ResourceType };
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
