import { useState, type MouseEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { Icon } from './Icon';
import './board.css';

/** One row of a board: when, what kind, from where, its status in words, and the name that links to it. */
export interface BoardRow {
  key: string;
  /** Short date in mono, e.g. "26.10"; `dateTime` is the machine-readable value. */
  when: string;
  dateTime?: string | undefined;
  tag: ReactNode;
  where?: string | null | undefined;
  status: ReactNode;
  name: string;
  to: string;
}

/**
 * A News or Events board on the home page (design system v3, NewsBoard): a 2px ink rule, the heading and a subtitle,
 * then rows that scroll up in a slow loop. The loop holds still on hover and keyboard focus, and a tap on the board
 * (not on a link) pauses or plays it, for touch users who have no hover. Under prefers-reduced-motion it never moves
 * and scrolls by hand (board.css).
 *
 * The rows are drawn twice so the loop has no seam. The second copy is hidden from assistive tech and its links are
 * out of the tab order: a screen reader and the keyboard meet each row once.
 */
export function Board({ id, title, subtitle, footnote, rows, loading, error, empty, onRetry }: {
  id: string;
  title: string;
  subtitle: string;
  footnote: string;
  rows: BoardRow[];
  loading: boolean;
  error: boolean;
  empty: string;
  onRetry: () => void;
}) {
  const { t } = useTranslation();
  const [paused, setPaused] = useState(false);
  const toggle = (e: MouseEvent) => {
    if ((e.target as HTMLElement).closest('a')) return;
    setPaused((p) => !p);
  };
  const heading = `${id}-h`;
  // A short list does not fill the board: it stays still, so no row runs past twice in view.
  const loops = rows.length >= MIN_LOOP_ROWS;

  return (
    <section className={`board${paused ? ' is-paused' : ''}`} aria-labelledby={heading}>
      <div className="board-head">
        <div className="board-title">
          <h2 id={heading}>{title}</h2>
          <span className="board-sub">{subtitle}</span>
          {paused && <span className="board-state" role="status">{t('board.paused')}</span>}
        </div>
      </div>
      <div aria-live="polite" aria-busy={loading}>
        {loading && (
          <div className="board-body">
            <span className="sr-only">{t('common.loading')}</span>
            {[0, 1, 2, 3].map((i) => <div key={i} className="skeleton-row" aria-hidden="true" />)}
          </div>
        )}
        {!loading && error && (
          <div role="alert" className="state-box state-error">
            <p><Icon name="warn" /> {t(`${id}.error`)}</p>
            <button type="button" className="btn" onClick={onRetry}><Icon name="refresh" /> {t('common.retry')}</button>
          </div>
        )}
        {!loading && !error && rows.length === 0 && <p className="state-box">{empty}</p>}
        {rows.length > 0 && (
          // A pointer target, not a control: the keyboard pauses it by focusing a link inside.
          // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
          <div className={`board-body${loops ? ' is-looping' : ''}`} onClick={toggle}>
            <div className="board-track">
              <ul className="plain-list" aria-label={title}>
                {rows.map((row) => <Row key={row.key} row={row} copy={false} />)}
              </ul>
              {loops && (
                <ul className="plain-list" aria-hidden="true">
                  {rows.map((row) => <Row key={row.key} row={row} copy />)}
                </ul>
              )}
            </div>
          </div>
        )}
      </div>
      <p className="board-foot">{footnote}</p>
    </section>
  );
}

/** Fewer rows than this fit the board's height, so they do not loop. */
const MIN_LOOP_ROWS = 5;

function Row({ row, copy }: { row: BoardRow; copy: boolean }) {
  return (
    <li className="board-row">
      <div className="board-meta">
        <time className="board-time" dateTime={row.dateTime}>{row.when}</time>
        {row.tag}
        {row.where && <span className="board-where">{row.where}</span>}
      </div>
      <span className="board-status">{row.status}</span>
      <Link className="board-name" to={row.to} tabIndex={copy ? -1 : undefined}>{row.name}</Link>
    </li>
  );
}
