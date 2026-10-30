import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { RecentResponse } from '@/api/types';
import { ALL_TYPES, cardDate, DAYS, SHOWN } from '@/components/Radar';
import { TypeTag } from '@/components/ui/TypeTag';
import { useApi } from '@/hooks/useApi';
import { shortDay } from '@/lib/format';
import { HomeBoard, type BoardRow } from './HomeBoard';

/**
 * News: the newest resources in Helper2's own index, by the date each source states (GET /recent, the same request
 * as the old Radar, so it shares its cache). A row with no stated date says when Helper2.ai first saw it.
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
