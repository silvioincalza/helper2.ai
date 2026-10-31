import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { RecentResponse, ResultCard } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { Board, type BoardRow } from './Board';
import { StatusPill } from './ui/StatusPill';
import { TypeTag } from './ui/TypeTag';

/**
 * Tools and research only: news and events come from a web-search API whose terms do not clearly allow
 * public redisplay (docs/sources.md), so the home page does not show them. Search still finds them.
 */
const ALL_TYPES: ResultCard['type'][] = ['MCP_SERVER', 'MODEL', 'LIBRARY', 'PAPER', 'AGENT', 'DATASET', 'PROMPT', 'SKILL', 'PRODUCT'];

/**
 * Data comes from GET /recent: Helper2's own index, newest by the date the SOURCE states, so the home page makes
 * no live source calls (GitHub and arXiv are rate-limited) and "new" really means newly published, not "matched a
 * keyword, then sorted". Everything from the last week, as many as the API gives (24).
 */
const SHOWN = 24;
const DAYS = 7;

/** The date a source stated (published, else updated); otherwise when Helper2.ai first saw it, labelled as such. */
export function cardDate(card: ResultCard): { key: 'published' | 'updated' | 'firstSeen'; value: string } | null {
  const { published, updated, discovered } = card.timeline;
  if (published.status !== 'UNKNOWN' && published.value) return { key: 'published', value: published.value };
  if (updated.status !== 'UNKNOWN' && updated.value) return { key: 'updated', value: updated.value };
  if (discovered.status === 'KNOWN' && discovered.value) return { key: 'firstSeen', value: discovered.value };
  return null;
}

/** "26.10": the board's short date, day then month, as a calendar day (UTC). */
export function dayMonth(value: string): string {
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return '';
  return `${String(d.getUTCDate()).padStart(2, '0')}.${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

/** News: what is new in the AI world this week, on the home page. */
export function NewsBoard() {
  const { t } = useTranslation();
  const params = new URLSearchParams({ days: String(DAYS), size: String(SHOWN) });
  for (const type of ALL_TYPES) params.append('type', type);
  const feed = useApi<RecentResponse>(`radar?${params.toString()}`, (signal) => api.listRecent(params, signal));
  const rows: BoardRow[] = (feed.data?.items ?? []).slice(0, SHOWN).map((card) => {
    const date = cardDate(card);
    return {
      key: card.id,
      when: date ? dayMonth(date.value) : '—',
      dateTime: date?.value,
      tag: <TypeTag type={card.type} />,
      where: card.sources[0]?.sourceName,
      status: date
        ? <StatusPill tone={date.key === 'firstSeen' ? 'warn' : 'ok'}>{t(`radar.${date.key}`)}</StatusPill>
        : <StatusPill tone="warn">{t('radar.noDate')}</StatusPill>,
      name: card.title,
      to: `/resource/${encodeURIComponent(card.id)}`,
    };
  });

  return (
    <Board id="radar" title={t('radar.title')} subtitle={t('radar.subtitle')} footnote={t('radar.footnote')}
      rows={rows} loading={feed.loading} error={!feed.loading && feed.error !== undefined}
      empty={t('radar.empty')} onRetry={feed.reload} />
  );
}
