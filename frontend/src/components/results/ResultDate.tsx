import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { shortMonth } from '@/lib/format';

/**
 * The date people scan for, on a result row and under the resource page's title: when it was last updated, else when
 * it was created, as a source states it. With neither, when Helper2 first saw it, in the "not stated" colour, so it is
 * never read as the source's date. Nothing when no date is known. [separator] puts " · " before it.
 */
export function ResultDate({ card, separator = false }: { card: ResultCard; separator?: boolean }) {
  const { t, i18n } = useTranslation();
  const date = scanDate(card);
  if (!date) return null;
  const month = shortMonth(date.value, i18n.language);
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
