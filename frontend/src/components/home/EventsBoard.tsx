import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { UpcomingEventsResponse } from '@/api/types';
import { EVENTS_SHOWN, startsSoon } from '@/components/NextEvents';
import { useApi } from '@/hooks/useApi';
import { countdown, daysUntil } from '@/lib/countdown';
import { shortDay } from '@/lib/format';
import { HomeBoard, type BoardRow } from './HomeBoard';

/**
 * Events: the next three months, soonest first, only events whose source states the dates (GET /events/upcoming, the
 * same request as the old Next events list). The status says how far away each one is; the next one stands out.
 */
export function EventsBoard() {
  const { t, i18n } = useTranslation();
  const feed = useApi<UpcomingEventsResponse>('next-events', (signal) =>
    api.listUpcomingEvents(new URLSearchParams({ size: String(EVENTS_SHOWN) }), signal));
  // The server's clock (generatedAt) decides the window and the countdown, as in the old Next events list.
  const now = Date.parse(feed.data?.generatedAt ?? '');
  const cards = (feed.data?.items ?? []).filter((card) => startsSoon(card, now));
  const rows: BoardRow[] = cards.map((card, i) => {
    const start = card.timeline.eventStart?.value ?? '';
    const days = daysUntil(start, now);
    const left = Number.isNaN(days) ? null : countdown(days);
    const kind = [card.eventKind && t(`events.kind.${card.eventKind}`), card.eventFormat && t(`events.format.${card.eventFormat}`)]
      .filter(Boolean).join(' · ');
    return {
      id: card.id,
      date: start ? shortDay(start, i18n.language) : '',
      dateTime: start || undefined,
      tag: kind ? <span className="h2-type-tag h2-type-tag--other">{kind}</span> : null,
      where: card.location ?? card.sources[0]?.sourceName,
      status: left ? t(`board.countdown.${left.key}`, 'count' in left ? { count: left.count } : {}) : '',
      tone: i === 0 ? 'next' : 'later',
      title: card.title,
      href: `/resource/${encodeURIComponent(card.id)}`,
    };
  });
  return (
    <HomeBoard id="events" title={t('board.events.title')} sub={t('board.events.sub')} listLabel={t('board.events.list')}
      foot={t('board.events.foot')} loading={feed.loading} error={feed.error !== undefined} errorText={t('events.error')}
      emptyText={t('events.empty')} onRetry={feed.reload} rows={rows} />
  );
}
