import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { shortDay } from '@/lib/format';
import { Skeleton, SkeletonRows } from '@/ui/Skeleton';
import type { EventRow, Feed, NewsRow } from './homeFeeds';
import type { TermCells } from './TermList';

export const FIRST_ROWS = 6;

export type ListId = 'news' | 'events';

export function useFirstRows<Row>(rows: Row[]) {
  const [all, setAll] = useState(false);
  return { all, toggle: () => setAll((a) => !a), shown: all ? rows : rows.slice(0, FIRST_ROWS) };
}

export function ShowAll({
  command,
  count,
  all,
  controls,
  onToggle,
}: {
  command: string;
  count: number;
  all: boolean;
  controls: string;
  onToggle: () => void;
}) {
  const { t } = useTranslation();
  return (
    <button
      type="button"
      className="h2-cmd"
      aria-expanded={all}
      aria-controls={controls}
      onClick={onToggle}
    >
      <span aria-hidden="true">$ {command} --all · </span>
      {all ? t('lists.showFewer') : t('lists.showAll', { count })}
    </button>
  );
}

function TermListSkeleton() {
  return (
    <Skeleton>
      <SkeletonRows rows={FIRST_ROWS} bars={[{ size: 'meta', width: 'full' }]} ruled />
    </Skeleton>
  );
}

export function ListState({
  feed,
  errorText,
  emptyText,
  level = 3,
  skeleton,
}: {
  feed: Feed<unknown>;
  errorText: string;
  emptyText: string;
  level?: number;
  skeleton?: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <div aria-live="polite">
      {feed.loading && (
        <>
          <span className="sr-only">{t('common.loading')}</span>
          {skeleton ?? <TermListSkeleton />}
        </>
      )}
      {!feed.loading && feed.error && (
        <StatePanel
          tone="error"
          level={level}
          title={errorText}
          next={<RetryButton onClick={feed.reload} />}
        />
      )}
      {!feed.loading && !feed.error && feed.rows.length === 0 && (
        <StatePanel tone="notice" level={level} title={emptyText} />
      )}
    </div>
  );
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
