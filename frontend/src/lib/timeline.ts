import type { ResultCard } from '@/api/types';

interface TimelineGroup {
  month: string | null;
  cards: ResultCard[];
}

export function creationDate(card: ResultCard): string | null {
  const { published } = card.timeline;
  return published.status !== 'UNKNOWN' && published.value ? published.value : null;
}

export function groupByCreationMonth(cards: ResultCard[]): TimelineGroup[] {
  const groups: TimelineGroup[] = [];
  const unknown: ResultCard[] = [];
  for (const card of cards) {
    const date = creationDate(card);
    const month = date ? utcMonth(date) : null;
    if (month === null) {
      unknown.push(card);
      continue;
    }
    const last = groups.at(-1);
    if (last && last.month === month) last.cards.push(card);
    else groups.push({ month, cards: [card] });
  }
  if (unknown.length > 0) groups.push({ month: null, cards: unknown });
  return groups;
}

function utcMonth(iso: string): string | null {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d.toISOString().slice(0, 7);
}
