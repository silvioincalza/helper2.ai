import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { api } from '@/api/client';
import type { RecentResponse, ResultCard, UpcomingEventsResponse } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { formatDate } from '@/lib/format';
import { NewsBoard, type BoardRow } from './ui/NewsBoard';
import { StatusPill } from './ui/StatusPill';
import { TypeTag } from './ui/TypeTag';

/**
 * Tools and research only: news and events come from a web-search API whose terms do not clearly allow
 * public redisplay (docs/sources.md), so the home page does not show them. Search still finds them.
 */
const NEWS_TYPES: ResultCard['type'][] = ['MCP_SERVER', 'MODEL', 'LIBRARY', 'PAPER', 'AGENT', 'DATASET', 'PROMPT', 'SKILL', 'PRODUCT'];
/** As many as GET /recent gives (24), from the last week. */
const NEWS_SIZE = 24;
const NEWS_DAYS = 7;
/** As many as GET /events/upcoming gives (60, enough for three months); only those starting in the next three months. */
const EVENTS_SIZE = 60;
const EVENTS_DAYS = 90;
const DAY_MS = 86_400_000;

const resourcePath = (card: ResultCard) => `/resource/${encodeURIComponent(card.id)}`;

/** The date a source stated (published, else updated); otherwise when Helper2.ai first saw it, labelled as such. */
function statedDate(card: ResultCard): { key: 'published' | 'updated' | 'firstSeen'; value: string; precision: 'INSTANT' | 'DAY' | 'UNKNOWN' } | null {
  const { published, updated, discovered } = card.timeline;
  if (published.status !== 'UNKNOWN' && published.value) return { key: 'published', value: published.value, precision: published.precision };
  if (updated.status !== 'UNKNOWN' && updated.value) return { key: 'updated', value: updated.value, precision: updated.precision };
  if (discovered.status === 'KNOWN' && discovered.value) return { key: 'firstSeen', value: discovered.value, precision: 'DAY' };
  return null;
}

function newsRow(card: ResultCard, t: TFunction, lang: string): BoardRow {
  const date = statedDate(card);
  return {
    id: card.id,
    date: date ? <time dateTime={date.value}>{formatDate(date.value, lang, date.precision)}</time> : t('board.news.noDate'),
    tag: <TypeTag type={card.type} />,
    where: card.sources[0]?.sourceName ?? null,
    status: date && <StatusPill tone={date.key === 'firstSeen' ? 'warn' : 'ok'}>{t(`board.news.${date.key}`)}</StatusPill>,
    name: card.title,
    to: resourcePath(card),
  };
}

/** "6–12 Dec 2026" from the days the source states; dates are calendar days, so format them in UTC. */
function eventDays(card: ResultCard, lang: string): string | null {
  const start = card.timeline.eventStart;
  if (!start || start.status === 'UNKNOWN' || !start.value) return null;
  const fmt = new Intl.DateTimeFormat(lang, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
  const end = card.timeline.eventEnd;
  const from = new Date(start.value);
  if (end && end.status !== 'UNKNOWN' && end.value && end.value !== start.value) return fmt.formatRange(from, new Date(end.value));
  return fmt.format(from);
}

/** Whole days from [now]'s UTC day to the event's first day; NaN when either is unknown. */
function daysUntil(card: ResultCard, now: number): number {
  const start = Date.parse(card.timeline.eventStart?.value ?? '');
  return Math.round((start - Math.floor(now / DAY_MS) * DAY_MS) / DAY_MS);
}

/** "in 13 days", "tomorrow"; "On now" once it has started (the API lists an event until its last day is over). */
function startsIn(days: number, t: TFunction, lang: string): string {
  return days <= 0 ? t('board.events.now') : new Intl.RelativeTimeFormat(lang, { numeric: 'auto' }).format(days, 'day');
}

function eventRow(card: ResultCard, index: number, now: number, t: TFunction, lang: string): BoardRow {
  const days = daysUntil(card, now);
  const place = [card.eventFormat && t(`board.events.format.${card.eventFormat}`), card.location].filter(Boolean).join(' · ');
  return {
    id: card.id,
    date: <time dateTime={card.timeline.eventStart?.value ?? undefined}>{eventDays(card, lang)}</time>,
    tag: <TypeTag type={card.type}>{card.eventKind && t(`board.events.kind.${card.eventKind}`)}</TypeTag>,
    where: place || (card.sources[0]?.sourceName ?? null),
    // The next event is outlined in accent; the ones after it in a quieter line.
    status: Number.isNaN(days) ? null : <StatusPill tone={index === 0 ? 'next' : 'later'}>{startsIn(days, t, lang)}</StatusPill>,
    name: card.title,
    to: resourcePath(card),
  };
}

/**
 * What is new in the index this week (GET /recent, newest by the date the source states) and the next AI events
 * (GET /events/upcoming, ADR 0022). Both read Helper2's own index: the home page makes no live source calls.
 */
export function HomeBoards() {
  const { t, i18n } = useTranslation();
  const lang = i18n.language;

  const newsParams = new URLSearchParams({ days: String(NEWS_DAYS), size: String(NEWS_SIZE) });
  for (const type of NEWS_TYPES) newsParams.append('type', type);
  const news = useApi<RecentResponse>(`news?${newsParams.toString()}`, (signal) => api.listRecent(newsParams, signal));

  const events = useApi<UpcomingEventsResponse>('events', (signal) =>
    api.listUpcomingEvents(new URLSearchParams({ size: String(EVENTS_SIZE) }), signal));
  // The window follows the server's clock (generatedAt); an event whose start is unknown stays listed.
  const now = Date.parse(events.data?.generatedAt ?? '');
  const upcoming = events.data?.items.filter((card) => !(daysUntil(card, now) > EVENTS_DAYS));

  return (
    <div className="home-boards">
      <NewsBoard title={t('board.news.title')} lede={t('board.news.lede')} failed={!news.loading && news.error !== undefined}
        rows={news.loading ? undefined : news.data?.items.slice(0, NEWS_SIZE).map((card) => newsRow(card, t, lang))}
        onRetry={news.reload} emptyText={t('board.news.empty')} errorText={t('board.news.error')} />
      <NewsBoard title={t('board.events.title')} lede={t('board.events.lede')} failed={!events.loading && events.error !== undefined}
        rows={events.loading ? undefined : upcoming?.map((card, i) => eventRow(card, i, now, t, lang))}
        onRetry={events.reload} emptyText={t('board.events.empty')} errorText={t('board.events.error')} />
    </div>
  );
}
