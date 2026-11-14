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
import { listTotal } from '@/lib/sourceCounts';

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
const NEWS_SIZE = 60;
const NEWS_DAYS = 7;
const EVENTS_SIZE = 60;
const EVENTS_DAYS = 90;

type DateKind = 'published' | 'updated' | 'firstSeen';

export interface NewsRow {
  id: string;
  date: string;
  dateKind: DateKind | null;
  type: ResourceType;
  title: string;
  href: string;
  host: string;
}

export interface EventRow {
  id: string;
  start: string;
  end: string | null;
  left: Countdown | null;
  kind: NonNullable<ResultCard['eventKind']> | null;
  format: NonNullable<ResultCard['eventFormat']> | null;
  location: string | null;
  aiRead: boolean;
  title: string;
  href: string;
}

export interface Feed<Row> {
  rows: Row[];
  total: number;
  now: number;
  loading: boolean;
  error: boolean;
  reload: () => void;
}

function hostOf(card: ResultCard): string {
  const source = card.sources[0];
  return source ? displayHost(source.url) : '';
}

function newsDate(card: ResultCard): { kind: DateKind; value: string } | null {
  const { published, updated, discovered } = card.timeline;
  const stated = (fact: DateFact) => fact.status !== 'UNKNOWN' && !!fact.value;
  if (stated(published)) return { kind: 'published', value: published.value as string };
  if (stated(updated)) return { kind: 'updated', value: updated.value as string };
  if (discovered.status === 'KNOWN' && discovered.value)
    return { kind: 'firstSeen', value: discovered.value };
  return null;
}

function toNewsRow(card: ResultCard): NewsRow {
  const date = newsDate(card);
  return {
    id: card.id,
    date: date?.value ?? '',
    dateKind: date?.kind ?? null,
    type: card.type,
    title: card.title,
    href: resourcePath(card.id, card.title),
    host: hostOf(card),
  };
}

function toEventRow(card: ResultCard, now: number): EventRow {
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
    aiRead: card.eventOrigin === 'AI_EXTRACTED',
    title: card.title,
    href: resourcePath(card.id, card.title),
  };
}

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
    now: Date.parse(feed.data?.generatedAt ?? ''),
    loading: feed.loading,
    error: feed.error !== undefined,
    reload: feed.reload,
  };
}

export function useEventsFeed(days = EVENTS_DAYS): Feed<EventRow> {
  const feed = useApi<UpcomingEventsResponse>(`next-events?days=${days}`, (signal) =>
    api.listUpcomingEvents(
      new URLSearchParams({ days: String(days), size: String(EVENTS_SIZE) }),
      signal,
    ),
  );
  const now = Date.parse(feed.data?.generatedAt ?? '');
  const rows = (feed.data?.items ?? []).map((card) => toEventRow(card, now));
  return {
    rows,
    total: listTotal(feed.data, rows.length),
    now,
    loading: feed.loading,
    error: feed.error !== undefined,
    reload: feed.reload,
  };
}

export function useAiNewsFeed(): Feed<NewsRow> {
  const params = new URLSearchParams({
    days: String(NEWS_DAYS),
    size: String(NEWS_SIZE),
    type: 'NEWS',
  });
  const feed = useApi<RecentResponse>(`news?${params.toString()}`, (signal) =>
    api.listRecent(params, signal),
  );
  const rows = (feed.data?.items ?? []).map(toNewsRow);
  return {
    rows,
    total: listTotal(feed.data, rows.length),
    now: Date.parse(feed.data?.generatedAt ?? ''),
    loading: feed.loading,
    error: feed.error !== undefined,
    reload: feed.reload,
  };
}
