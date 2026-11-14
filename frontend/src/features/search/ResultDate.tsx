import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { shortMonth } from '@/lib/format';

export function ResultDate({ card, separator = false }: { card: ResultCard; separator?: boolean }) {
  const { t } = useTranslation();
  const date = scanDate(card);
  if (!date) return null;
  const month = shortMonth(date.value);
  if (!month) return null;
  return (
    <>
      {separator && <span aria-hidden="true"> · </span>}
      <time dateTime={date.value} className={date.ours ? 'h2-date is-ours' : 'h2-date'}>
        {t(date.key, { date: month })}
      </time>
    </>
  );
}

function scanDate(card: ResultCard): { key: string; value: string; ours: boolean } | null {
  const { updated, published, discovered } = card.timeline;
  if (updated.status !== 'UNKNOWN' && updated.value)
    return { key: 'results.dateUpdated', value: updated.value, ours: false };
  if (published.status !== 'UNKNOWN' && published.value)
    return { key: 'results.dateCreated', value: published.value, ours: false };
  if (discovered.status !== 'UNKNOWN' && discovered.value)
    return { key: 'results.dateFirstSeen', value: discovered.value, ours: true };
  return null;
}
