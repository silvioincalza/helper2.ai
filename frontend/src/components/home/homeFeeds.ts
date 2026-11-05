import { api } from '@/api/client';
import type {
  DateFact,
  RecentResponse,
  ResourceType,
  ResultCard,
  UpcomingEventsResponse,
} from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { countdown, daysUntil, type Countdown } from '@/lib/countdown';
import { displayHost } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';
import { listTotal } from '@/lib/v4Fields';

/**
 * Tools and research only: news and events come from a web-search API whose terms do not clearly allow
 * public redisplay (docs/sources.md), so the home page does not show them. Search still finds them.
 */
const NEWS_TYPES: ResourceType[] = [
  'MCP_SERVER',
  'MODEL',
  'LIBRARY',
  'PAPER',
  'AGENT',
  'DATASET',
  'PROMPT',
  'SKILL',
  'PRODUCT',
];
/**
 * GET /recent: Helper2's own index, newest by the date the SOURCE states, so the home page makes no live source calls
 * (GitHub and arXiv are rate-limited) and "new" means newly published. The last week, as many as the API gives.
 */
const NEWS_SIZE = 24;
const NEWS_DAYS = 7;
/** The next three months (the API's `days` window, so `total` counts the same events); as many as it gives (60). */
const EVENTS_SIZE = 60;
const EVENTS_DAYS = 90;

/** Which date a row shows: the one its source stated, else the day Helper2.ai first saw it. */
export type DateKind = 'published' | 'updated' | 'firstSeen';

export interface NewsRow {
  id: string;
  /** ISO date of the row, or '' when no date is known. */
  date: string;
  dateKind: DateKind | null;
  type: ResourceType;
  title: string;
  href: string;
  /** Where it was found: the source page's host ("github.com"). */
  host: string;
}

export interface EventRow {
  id: string;
  /** ISO start day. */
  start: string;
  end: string | null;
  /** How far away it is, from the server's clock; null when the start cannot be read. */
  left: Countdown | null;
  kind: NonNullable<ResultCard['eventKind']> | null;
  format: NonNullable<ResultCard['eventFormat']> | null;
  location: string | null;
  title: string;
  href: string;
}

/** One list's state: its rows, how many the whole list holds, and loading, error and retry. */
export interface Feed<Row> {
  rows: Row[];
  total: number;
  loading: boolean;
  error: boolean;
  reload: () => void;
}

function hostOf(card: ResultCard): string {
  const source = card.sources[0];
  return source ? displayHost(source.url) : '';
}

/** The date a source stated (published, else updated); otherwise when Helper2.ai first saw it. */
function newsDate(card: ResultCard): { kind: DateKind; value: string } | null {
  const { published, updated, discovered } = card.timeline;
  const stated = (fact: DateFact) => fact.status !== 'UNKNOWN' && !!fact.value;
  if (stated(published)) return { kind: 'published', value: published.value as string };
  if (stated(updated)) return { kind: 'updated', value: updated.value as string };
  if (discovered.status === 'KNOWN' && discovered.value)
    return { kind: 'firstSeen', value: discovered.value };
  return null;
}

export function toNewsRow(card: ResultCard): NewsRow {
  const date = newsDate(card);
  return {
    id: card.id,
    date: date?.value ?? '',
    dateKind: date?.kind ?? null,
    type: card.type,
    title: card.title,
    href: resourcePath(card.id),
    host: hostOf(card),
  };
}

export function toEventRow(card: ResultCard, now: number): EventRow {
  const start = card.timeline.eventStart?.value ?? '';
  const days = start ? daysUntil(start, now) : Number.NaN;
  return {
    id: card.id,
    start,
    end: card.timeline.eventEnd?.value ?? null,
    left: Number.isNaN(days) ? null : countdown(days),
    kind: card.eventKind ?? null,
    format: card.eventFormat ?? null,
    location: card.location ?? null,
    title: card.title,
    href: resourcePath(card.id),
  };
}

/** New this week: the newest resources in Helper2's own index (GET /recent). A row is its title, never a description. */
export function useNewsFeed(): Feed<NewsRow> {
  const params = new URLSearchParams({ days: String(NEWS_DAYS), size: String(NEWS_SIZE) });
  for (const type of NEWS_TYPES) params.append('type', type);
  const feed = useApi<RecentResponse>(`radar?${params.toString()}`, (signal) =>
    api.listRecent(params, signal),
  );
  const rows = (feed.data?.items ?? []).map(toNewsRow);
  return {
    rows,
    total: listTotal(feed.data, rows.length),
    loading: feed.loading,
    error: feed.error !== undefined,
    reload: feed.reload,
  };
}

/** Coming up: the next three months, soonest first, only events whose source states the dates. */
export function useEventsFeed(): Feed<EventRow> {
  const feed = useApi<UpcomingEventsResponse>(`next-events?days=${EVENTS_DAYS}`, (signal) =>
    api.listUpcomingEvents(
      new URLSearchParams({ days: String(EVENTS_DAYS), size: String(EVENTS_SIZE) }),
      signal,
    ),
  );
  // The server's clock (generatedAt) decides how far away each event is.
  const now = Date.parse(feed.data?.generatedAt ?? '');
  const rows = (feed.data?.items ?? []).map((card) => toEventRow(card, now));
  return {
    rows,
    total: listTotal(feed.data, rows.length),
    loading: feed.loading,
    error: feed.error !== undefined,
    reload: feed.reload,
  };
}
