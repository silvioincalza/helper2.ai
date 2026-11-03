import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { StatusPill, type StatusTone } from '@/components/ui/StatusPill';
import './home.css';

/** One row of a list: when, what kind, from where, its status in words, the name that opens it, and a line about it. */
export interface BoardRow {
  id: string;
  date: string;
  dateTime?: string | undefined;
  tag: ReactNode;
  where?: string | undefined;
  status: string;
  tone: StatusTone;
  title: string;
  href: string;
  /** One line from the source, when it gives one. */
  text?: string | null | undefined;
}

/** Rows shown before "Show all": a short list reads at a glance. */
export const BOARD_FIRST = 6;

interface Props {
  id: string;
  title: string;
  sub: string;
  listLabel: string;
  loading: boolean;
  error: boolean;
  errorText: string;
  emptyText: string;
  onRetry: () => void;
  rows: BoardRow[];
}

/**
 * A News or Events list on the home page (ADR 0040, 2): a still list, newest or soonest first. The first rows show;
 * "Show all" opens the rest in place. The section's id is the header link's target (#news, #events).
 */
export function HomeBoard({ id, title, sub, listLabel, loading, error, errorText, emptyText, onRetry, rows }: Props) {
  const { t } = useTranslation();
  const [all, setAll] = useState(false);
  const shown = all ? rows : rows.slice(0, BOARD_FIRST);
  const listId = `${id}-list`;

  return (
    <section id={id} className="h2-board" aria-labelledby={`${id}-h`}>
      <div className="h2-board__head">
        <h2 id={`${id}-h`} className="h2-board__title">{title}</h2>
        <span className="h2-board__sub">{sub}</span>
      </div>
      <div aria-live="polite" aria-busy={loading}>
        {loading && (
          <div>
            <span className="sr-only">{t('common.loading')}</span>
            {[0, 1, 2].map((i) => <div key={i} className="skeleton-row" aria-hidden="true" />)}
          </div>
        )}
        {!loading && error && (
          <StatePanel tone="error" level={3} title={errorText} next={<RetryButton onClick={onRetry} />} />
        )}
        {!loading && !error && rows.length === 0 && <StatePanel tone="notice" level={3} title={emptyText} />}
      </div>
      {rows.length > 0 && (
        <>
          <ul id={listId} className="plain-list h2-board__list" aria-label={listLabel}>
            {shown.map((row) => <Row key={row.id} row={row} />)}
          </ul>
          {rows.length > BOARD_FIRST && (
            <button type="button" className="btn btn-ghost h2-board__more" aria-expanded={all} aria-controls={listId}
              onClick={() => setAll((a) => !a)}>
              {all ? t('board.showFewer') : t('board.showAll', { count: rows.length })}
            </button>
          )}
        </>
      )}
    </section>
  );
}

function Row({ row }: { row: BoardRow }) {
  return (
    <li className="h2-board__row">
      <div className="h2-board__meta">
        {row.date && <time className="h2-board__date" dateTime={row.dateTime}>{row.date}</time>}
        {row.tag}
        {row.where && <span className="h2-board__where">{row.where}</span>}
      </div>
      {row.status ? <StatusPill tone={row.tone}>{row.status}</StatusPill> : <span />}
      <Link className="h2-board__name" to={row.href}>{row.title}</Link>
      {row.text && <p className="h2-board__text">{row.text}</p>}
    </li>
  );
}
