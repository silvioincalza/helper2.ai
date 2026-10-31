import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { RecentResponse, ResultCard } from '@/api/types';
import { TypeTag } from '@/components/ui/TypeTag';
import { useApi } from '@/hooks/useApi';
import { shortDay } from '@/lib/format';
import { HomeBoard, type BoardRow } from './HomeBoard';

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
function cardDate(card: ResultCard): { key: 'published' | 'updated' | 'firstSeen'; value: string; precision: 'INSTANT' | 'DAY' | 'UNKNOWN' } | null {
  const { published, updated, discovered } = card.timeline;
  if (published.status !== 'UNKNOWN' && published.value) return { key: 'published', value: published.value, precision: published.precision };
  if (updated.status !== 'UNKNOWN' && updated.value) return { key: 'updated', value: updated.value, precision: updated.precision };
  if (discovered.status === 'KNOWN' && discovered.value) return { key: 'firstSeen', value: discovered.value, precision: 'DAY' };
  return null;
}

/**
 * News: the newest resources in Helper2's own index, by the date each source states (GET /recent). A row with no stated date says when Helper2.ai first saw it.
 */
export function NewsBoard() {
  const { t, i18n } = useTranslation();
  const params = new URLSearchParams({ days: String(DAYS), size: String(SHOWN) });
  for (const type of ALL_TYPES) params.append('type', type);
  const feed = useApi<RecentResponse>(`radar?${params.toString()}`, (signal) => api.listRecent(params, signal));
  const rows: BoardRow[] = (feed.data?.items ?? []).slice(0, SHOWN).map((card) => {
    const date = cardDate(card);
    return {
      id: card.id,
      date: date ? shortDay(date.value, i18n.language) : '',
      dateTime: date?.value,
      tag: <TypeTag type={card.type} />,
      where: card.sources[0]?.sourceName,
      status: date ? t(`radar.${date.key}`) : t('radar.noDate'),
      tone: date && date.key !== 'firstSeen' ? 'ok' : 'warn',
      title: card.title,
      href: `/resource/${encodeURIComponent(card.id)}`,
    };
  });
  return (
    <HomeBoard id="news" title={t('board.news.title')} sub={t('board.news.sub')} listLabel={t('board.news.list')}
      foot={t('board.news.foot')} loading={feed.loading} error={feed.error !== undefined} errorText={t('radar.error')}
      emptyText={t('radar.empty')} onRetry={feed.reload} rows={rows} />
  );
}
