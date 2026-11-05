import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { shortDay } from '@/lib/format';
import type { EventRow, Feed, NewsRow } from './homeFeeds';
import type { TermCells } from './TermList';

/** The parts New this week and Coming up share on every width: the tables (HomeLists) and the phone tabs (HomeTabs). */
/** Rows shown before "show all": a short list reads at a glance. */
export const FIRST_ROWS = 6;

export type ListId = 'news' | 'events';

export function useFirstRows<Row>(rows: Row[]) {
  const [all, setAll] = useState(false);
  return { all, toggle: () => setAll((a) => !a), shown: all ? rows : rows.slice(0, FIRST_ROWS) };
}

/** `$ helper2 news --all · show all 38`: opens the rest of the list in place. Only when there is more to show. */
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
      className="v4-cmd"
      aria-expanded={all}
      aria-controls={controls}
      onClick={onToggle}
    >
      <span aria-hidden="true">$ {command} --all · </span>
      {all ? t('lists.showFewer') : t('lists.showAll', { count })}
    </button>
  );
}

/**
 * Loading, error and empty, announced politely; the rows themselves are not read aloud on load. [level] is the
 * heading level of the error and empty panels: 3 under a list heading, 2 right under a page title.
 */
export function ListState({
  feed,
  errorText,
  emptyText,
  level = 3,
}: {
  feed: Feed<unknown>;
  errorText: string;
  emptyText: string;
  level?: number;
}) {
  const { t } = useTranslation();
  return (
    <div aria-live="polite" aria-busy={feed.loading}>
      {feed.loading && (
        <div className="v4-skeleton">
          <span className="sr-only">{t('common.loading')}</span>
          {[0, 1, 2].map((i) => (
            <span key={i} className="v4-skeleton__line" aria-hidden="true" />
          ))}
        </div>
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
  const { t, i18n } = useTranslation();
  if (!row.date) return <span className="v4-not-stated">{t('lists.noDate')}</span>;
  const day = shortDay(row.date, i18n.language, now);
  return (
    <time
      dateTime={row.date}
      className={row.dateKind === 'firstSeen' ? 'v4-not-stated' : undefined}
    >
      {row.dateKind === 'firstSeen' ? t('lists.firstSeen', { date: day }) : day}
    </time>
  );
}

function EventWhen({ row, now }: { row: EventRow; now: number }) {
  const { t, i18n } = useTranslation();
  if (row.left?.key === 'onNow') return <span className="v4-on-now">{t('lists.onNow')}</span>;
  return row.start ? (
    <time dateTime={row.start}>{shortDay(row.start, i18n.language, now)}</time>
  ) : null;
}

function useEventText() {
  const { t, i18n } = useTranslation();
  return {
    kind: (row: EventRow) => (row.kind ? t(`events.kind.${row.kind}`) : ''),
    where: (row: EventRow) => row.location ?? (row.format ? t(`events.format.${row.format}`) : ''),
    /** In 2 days, tomorrow; for a running event, until when. */
    left: (row: EventRow, now: number) => {
      const left = row.left;
      if (!left) return '';
      if (left.key === 'onNow')
        return row.end ? t('lists.until', { date: shortDay(row.end, i18n.language, now) }) : '';
      return t(`board.countdown.${left.key}`, 'count' in left ? { count: left.count } : {});
    },
  };
}

/** A news row in TermList's cells: date, type, title, host. [now] is the server's clock (Feed.now). */
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

/** An event row in TermList's cells: when, kind, title, where, and how far away (both layouts). */
export function useEventCells(): (row: EventRow, now: number) => TermCells {
  const text = useEventText();
  return (row, now) => ({
    when: <EventWhen row={row} now={now} />,
    type: text.kind(row),
    title: row.title,
    href: row.href,
    where: text.where(row),
    note: text.left(row, now),
  });
}
