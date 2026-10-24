import type { ResultCard } from '@/api/types';

/** One month of the timeline; `month` is `YYYY-MM` (UTC), or null for items whose creation date no source states. */
export interface TimelineGroup {
  month: string | null;
  cards: ResultCard[];
}

/**
 * The date the item was created, as its source states it (the `published` fact: repo created, model created,
 * paper submitted, server published). Never Helper2's own discovery date: that is when we scanned it, not when
 * it was made. Returns null when the date is unknown.
 */
export function creationDate(card: ResultCard): string | null {
  const { published } = card.timeline;
  return published.status !== 'UNKNOWN' && published.value ? published.value : null;
}

/**
 * Groups cards by creation month, keeping the order they arrive in. The API sorts NEWEST first with unknown
 * dates last, so groups come out newest first and the "unknown" group, if any, comes last.
 */
export function groupByCreationMonth(cards: ResultCard[]): TimelineGroup[] {
  const groups: TimelineGroup[] = [];
  for (const card of cards) {
    const date = creationDate(card);
    const month = date ? utcMonth(date) : null;
    const last = groups.at(-1);
    if (last && last.month === month) last.cards.push(card);
    else groups.push({ month, cards: [card] });
  }
  return groups;
}

function utcMonth(iso: string): string | null {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d.toISOString().slice(0, 7);
}

/** "October 2026" for `2026-10`, in the reader's language. */
export function formatMonth(month: string, lang: string): string {
  const d = new Date(`${month}-01T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return month;
  return new Intl.DateTimeFormat(lang, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(d);
}
