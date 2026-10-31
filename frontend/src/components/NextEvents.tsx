import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { ResultCard, UpcomingEventsResponse } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { Board, type BoardRow } from './Board';
import { dayMonth } from './Radar';
import { StatusPill } from './ui/StatusPill';
import { TypeTag } from './ui/TypeTag';

/** As many as the API gives (60, enough for three months); only those starting in the next three months are listed. */
const SHOWN = 60;
const WINDOW_DAYS = 90;
const DAY_MS = 86_400_000;

/** Starts within WINDOW_DAYS of `now` (the server's `generatedAt`, so the window follows the server's clock). */
function startsSoon(card: ResultCard, now: number): boolean {
  const start = Date.parse(card.timeline.eventStart?.value ?? '');
  return Number.isNaN(now) || Number.isNaN(start) || start <= now + WINDOW_DAYS * DAY_MS;
}

/** Whole calendar days from `now` to the event's first day, or null without a stated start. */
export function daysUntil(card: ResultCard, now: number): number | null {
  const start = Date.parse(card.timeline.eventStart?.value ?? '');
  if (Number.isNaN(start) || Number.isNaN(now)) return null;
  const day = (ms: number) => Math.floor(ms / DAY_MS);
  return day(start) - day(now);
}

/** "Today", "Tomorrow", "In 5 days", "In 3 weeks", "In 2 months". */
export function untilLabel(days: number, t: TFunction): string {
  if (days <= 0) return t('events.until.today');
  if (days === 1) return t('events.until.tomorrow');
  if (days < 14) return t('events.until.days', { count: days });
  if (days < 45) return t('events.until.weeks', { count: Math.round(days / 7) });
  return t('events.until.months', { count: Math.round(days / 30) });
}

/**
 * Events: the next AI conferences, hackathons, meetups, talks and workshops, soonest first. Only events whose source
 * states when they take place are listed (GET /events/upcoming); nothing is called live. Web-search (Tavily) events
 * never state dates, so they never appear here (see the note in Radar). The next one is outlined.
 */
export function EventsBoard() {
  const { t } = useTranslation();
  const feed = useApi<UpcomingEventsResponse>('next-events', (signal) =>
    api.listUpcomingEvents(new URLSearchParams({ size: String(SHOWN) }), signal));
  const now = Date.parse(feed.data?.generatedAt ?? '');
  const cards = (feed.data?.items ?? []).filter((card) => startsSoon(card, now));
  const rows: BoardRow[] = cards.map((card, i) => {
    const start = card.timeline.eventStart?.value ?? undefined;
    const days = daysUntil(card, now);
    const where = [card.location, card.eventFormat && t(`events.format.${card.eventFormat}`)].filter(Boolean).join(' · ');
    return {
      key: card.id,
      when: start ? dayMonth(start) : '—',
      dateTime: start,
      tag: card.eventKind
        ? <span className="h2-type-tag h2-type-tag--other">{t(`events.kind.${card.eventKind}`)}</span>
        : <TypeTag type={card.type} />,
      where: where || card.sources[0]?.sourceName,
      status: days === null ? null : <StatusPill tone={i === 0 ? 'next' : 'later'}>{untilLabel(days, t)}</StatusPill>,
      name: card.title,
      to: `/resource/${encodeURIComponent(card.id)}`,
    };
  });

  return (
    <Board id="events" title={t('events.title')} subtitle={t('events.subtitle')} footnote={t('events.footnote')}
      rows={rows} loading={feed.loading} error={!feed.loading && feed.error !== undefined}
      empty={t('events.empty')} onRetry={feed.reload} />
  );
}
