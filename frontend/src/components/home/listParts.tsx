import { useState, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { shortDay } from '@/lib/format';
import type { EventRow, Feed, NewsRow } from './homeFeeds';

/** The parts New this week and Coming up share on every width: the tables (HomeLists) and the phone tabs (HomeTabs). */
/** Rows shown before "show all": a short list reads at a glance. */
export const FIRST_ROWS = 6;

export type ListId = 'news' | 'events';

/** The first rows print in once, 120 ms apart (ADR 0042, decision 6); rows opened later just appear. */
export const printIn = (i: number): CSSProperties | undefined =>
  i < FIRST_ROWS ? ({ '--i': i } as CSSProperties) : undefined;

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

/** Loading, error and empty, announced politely; the rows themselves are not read aloud on load. */
export function ListState({
  feed,
  errorText,
  emptyText,
}: {
  feed: Feed<unknown>;
  errorText: string;
  emptyText: string;
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
          level={3}
          title={errorText}
          next={<RetryButton onClick={feed.reload} />}
        />
      )}
      {!feed.loading && !feed.error && feed.rows.length === 0 && (
        <StatePanel tone="notice" level={3} title={emptyText} />
      )}
    </div>
  );
}

/** The cursor after a list's command: it blinks three times, then stays on. */
export const Cursor = () => <span className="v4-cursor" aria-hidden="true" />;

export function ListHead({
  id,
  title,
  total,
}: {
  id: ListId;
  title: string;
  total: number | null;
}) {
  return (
    <div className="v4-list__head">
      <h2 id={`${id}-h`} className="v4-list__title">
        <span className="v4-prompt" aria-hidden="true">
          ${' '}
        </span>
        {title}
        <Cursor />
      </h2>
      {total !== null && <span className="v4-list__total">{total}</span>}
    </div>
  );
}

export function NewsDate({ row }: { row: NewsRow }) {
  const { t, i18n } = useTranslation();
  if (!row.date) return <span className="v4-not-stated">{t('lists.noDate')}</span>;
  const day = shortDay(row.date, i18n.language);
  return (
    <time
      dateTime={row.date}
      className={row.dateKind === 'firstSeen' ? 'v4-not-stated' : undefined}
    >
      {row.dateKind === 'firstSeen' ? t('lists.firstSeen', { date: day }) : day}
    </time>
  );
}

export function EventWhen({ row }: { row: EventRow }) {
  const { t, i18n } = useTranslation();
  if (row.left?.key === 'onNow') return <span className="v4-on-now">{t('lists.onNow')}</span>;
  return row.start ? <time dateTime={row.start}>{shortDay(row.start, i18n.language)}</time> : null;
}

export function useEventText() {
  const { t, i18n } = useTranslation();
  return {
    kind: (row: EventRow) => (row.kind ? t(`events.kind.${row.kind}`) : ''),
    where: (row: EventRow) => row.location ?? (row.format ? t(`events.format.${row.format}`) : ''),
    /** In 2 days, tomorrow; for a running event, until when. */
    left: (row: EventRow) => {
      const left = row.left;
      if (!left) return '';
      if (left.key === 'onNow')
        return row.end ? t('lists.until', { date: shortDay(row.end, i18n.language) }) : '';
      return t(`board.countdown.${left.key}`, 'count' in left ? { count: left.count } : {});
    },
  };
}
