import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { shortDay } from '@/lib/format';
import type { EventRow, NewsRow } from './feeds';

export interface TermCells {
  when: ReactNode;
  type: string;
  title: string;
  href: string;
  where: string;
  note?: string;
  ai?: boolean;
}

function NewsDate({ row, now }: { row: NewsRow; now: number }) {
  const { t } = useTranslation();
  if (!row.date) return <span className="h2-not-stated">{t('lists.noDate')}</span>;
  const day = shortDay(row.date, now);
  return (
    <time
      dateTime={row.date}
      className={row.dateKind === 'firstSeen' ? 'h2-not-stated' : undefined}
    >
      {row.dateKind === 'firstSeen' ? t('lists.firstSeen', { date: day }) : day}
    </time>
  );
}

function EventWhen({ row, now }: { row: EventRow; now: number }) {
  const { t } = useTranslation();
  if (row.left?.key === 'onNow') return <span className="h2-on-now">{t('lists.onNow')}</span>;
  return row.start ? <time dateTime={row.start}>{shortDay(row.start, now)}</time> : null;
}

export function useEventText() {
  const { t } = useTranslation();
  return {
    kind: (row: EventRow) => (row.kind ? t(`events.kind.${row.kind}`) : ''),
    where: (row: EventRow) => row.location ?? (row.format ? t(`events.format.${row.format}`) : ''),
    left: (row: EventRow, now: number) => {
      const left = row.left;
      if (!left) return '';
      if (left.key === 'onNow')
        return row.end ? t('lists.until', { date: shortDay(row.end, now) }) : '';
      return t(`board.countdown.${left.key}`, 'count' in left ? { count: left.count } : {});
    },
  };
}

export function useNewsCells(): (row: NewsRow, now: number) => TermCells {
  const { t } = useTranslation();
  return (row, now) => ({
    when: <NewsDate row={row} now={now} />,
    type: t(`type.${row.type}`),
    title: row.title,
    href: row.href,
    where: row.host ?? '',
  });
}

export function useEventCells(): (row: EventRow, now: number) => TermCells {
  const text = useEventText();
  return (row, now) => ({
    when: <EventWhen row={row} now={now} />,
    type: text.kind(row),
    title: row.title,
    href: row.href,
    where: text.where(row),
    note: text.left(row, now),
    ai: row.aiRead,
  });
}
