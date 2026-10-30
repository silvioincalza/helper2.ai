import { useEffect, useId, useRef, useState, type PointerEvent, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { Icon } from '../Icon';
import './ui.css';

/** One row: the date, the type tag, the source or the place, the status pill, and the name linking to [to]. */
export interface BoardRow {
  id: string;
  date: ReactNode;
  tag: ReactNode;
  where: string | null;
  status: ReactNode;
  name: string;
  to: string;
}

/** About this many rows fit in `--board-height`; a board with more moves by itself. */
const ROWS_IN_VIEW = 4;
/** One row up every few seconds: slow enough to read a row before it moves. */
const ROW_MS = 4000;

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** The first row at the top of the window. */
function topRow(el: HTMLElement): number {
  return Math.max(0, (Array.from(el.children) as HTMLElement[]).findIndex((r) => r.offsetTop >= el.scrollTop - 1));
}

/**
 * News or Events on the home page (ADR 0040, 2): a heading, a short subtitle, and the rows in a window of
 * `--board-height`. [rows] is undefined while loading; [failed] shows the error with Retry.
 */
export function NewsBoard({ title, lede, rows, failed, onRetry, emptyText, errorText }: {
  title: string;
  lede: string;
  rows: BoardRow[] | undefined;
  failed: boolean;
  onRetry: () => void;
  emptyText: string;
  errorText: string;
}) {
  const { t } = useTranslation();
  const titleId = useId();
  const loading = rows === undefined && !failed;
  return (
    <section className="h2-board" aria-labelledby={titleId}>
      <h2 id={titleId} className="h2-board__title">{title}</h2>
      <p className="h2-board__lede">{lede}</p>
      <div aria-live="polite" aria-busy={loading}>
        {loading && (
          <div>
            <span className="sr-only">{t('common.loading')}</span>
            {Array.from({ length: ROWS_IN_VIEW }, (_, i) => <div key={i} className="skeleton-row h2-board__skeleton" aria-hidden="true" />)}
          </div>
        )}
        {failed && (
          <div role="alert" className="state-box state-error">
            <p><Icon name="warn" /> {errorText}</p>
            <button type="button" className="btn" onClick={onRetry}><Icon name="refresh" /> {t('common.retry')}</button>
          </div>
        )}
        {!failed && rows?.length === 0 && <p className="state-box">{emptyText}</p>}
        {!failed && rows !== undefined && rows.length > 0 && <MovingList rows={rows} label={title} />}
      </div>
    </section>
  );
}

/**
 * The rows, scrolling up one row every few seconds and back to the top at the end; by hand (wheel, touch, keyboard)
 * at any time. It holds still while a mouse is over it or focus is inside; a tap pauses it and a second tap plays it.
 * A visible Pause/Play button does the same for everyone (WCAG 2.2.2). With reduced motion it never moves by itself.
 * One copy of each row and no live region, so a screen reader reads a normal list.
 */
function MovingList({ rows, label }: { rows: BoardRow[]; label: string }) {
  const { t } = useTranslation();
  const track = useRef<HTMLUListElement>(null);
  const [hover, setHover] = useState(false);
  const [focus, setFocus] = useState(false);
  const [paused, setPaused] = useState(false);
  const [reduced] = useState(prefersReducedMotion);
  const moves = rows.length > ROWS_IN_VIEW && !reduced;

  useEffect(() => {
    if (!moves || hover || focus || paused) return;
    const id = window.setInterval(() => {
      const el = track.current;
      if (!el) return;
      const items = Array.from(el.children) as HTMLElement[];
      const atEnd = el.scrollTop + el.clientHeight >= el.scrollHeight - 1;
      const next = atEnd ? 0 : Math.min(topRow(el) + 1, items.length - 1);
      el.scrollTo?.({ top: items[next]?.offsetTop ?? 0, behavior: 'smooth' });
    }, ROW_MS);
    return () => window.clearInterval(id);
  }, [moves, hover, focus, paused]);

  // A touch has no hover: a tap outside a link or button toggles the board. A drag that scrolls ends in
  // pointercancel, not pointerup, so scrolling by hand never toggles it.
  const onTap = (e: PointerEvent) => {
    if (e.pointerType !== 'touch' || (e.target as Element).closest('a, button')) return;
    setPaused((p) => !p);
  };

  return (
    <div className="h2-board__window"
      onPointerEnter={(e) => { if (e.pointerType !== 'touch') setHover(true); }}
      onPointerLeave={() => setHover(false)}
      onPointerUp={onTap}
      onFocus={() => setFocus(true)}
      onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget)) setFocus(false); }}>
      <ul ref={track} className="h2-board__list" aria-label={label} aria-live="off">
        {rows.map((r) => (
          <li key={r.id} className="h2-board__row">
            <p className="h2-board__meta">
              <span className="h2-board__date">{r.date}</span>
              {r.tag}
              {r.where && <span className="h2-board__where">{r.where}</span>}
              {r.status}
            </p>
            <Link className="h2-board__name" to={r.to}>{r.name}</Link>
          </li>
        ))}
      </ul>
      {moves && (
        <button type="button" className="btn h2-board__toggle" onClick={() => setPaused((p) => !p)}>
          <Icon name={paused ? 'play' : 'pause'} /> {t(paused ? 'board.play' : 'board.pause', { label })}
        </button>
      )}
    </div>
  );
}
