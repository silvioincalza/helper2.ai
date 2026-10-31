import { useEffect, useState, type MouseEvent, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { StatusPill, type StatusTone } from '@/components/ui/StatusPill';
import { prefersReducedMotion, REDUCED_MOTION_QUERY } from '@/lib/motion';
import './home.css';

/** One row of a board: when, what kind, from where, its status in words, and the name that opens it. */
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
}

/** Fewer rows than this fit in view: the board does not loop. */
const LOOP_MIN = 5;

function useReducedMotion(): boolean {
  const [reduce, setReduce] = useState(prefersReducedMotion);
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const mq = window.matchMedia(REDUCED_MOTION_QUERY);
    const onChange = () => setReduce(mq.matches);
    mq.addEventListener?.('change', onChange);
    return () => mq.removeEventListener?.('change', onChange);
  }, []);
  return reduce;
}

interface Props {
  id: string;
  title: string;
  sub: string;
  listLabel: string;
  foot: string;
  loading: boolean;
  error: boolean;
  errorText: string;
  emptyText: string;
  onRetry: () => void;
  rows: BoardRow[];
}

/**
 * A News or Events board (ADR 0040, 2): the rows scroll slowly on their own, like a station board. Hover, keyboard
 * focus and a tap pause it (a tap again plays); under prefers-reduced-motion it stands still and scrolls by hand.
 * The loop shows the rows twice; the second copy is hidden from assistive tech and from the Tab key, so each row is
 * read and reached once.
 */
export function HomeBoard({ id, title, sub, listLabel, foot, loading, error, errorText, emptyText, onRetry, rows }: Props) {
  const { t } = useTranslation();
  const reduce = useReducedMotion();
  const [paused, setPaused] = useState(false);
  const loop = !reduce && rows.length >= LOOP_MIN;
  const toggle = (e: MouseEvent<HTMLDivElement>) => {
    if ((e.target as Element).closest('a')) return;
    setPaused((p) => !p);
  };
  const cls = `h2-board${loop ? ' is-loop' : ' is-static'}${paused ? ' is-paused' : ''}`;

  return (
    <section className={cls} aria-labelledby={`${id}-h`}>
      <div className="h2-board__head">
        <h2 id={`${id}-h`} className="h2-board__title">{title}</h2>
        <span className="h2-board__sub">{sub}</span>
        {loop && paused && <span className="h2-board__state" role="status">{t('board.paused')}</span>}
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
        // A tap pauses or plays the loop; it is a touch extra, as hover and keyboard focus already pause it.
        // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
        <div className="h2-board__body" onClick={loop ? toggle : undefined}>
          <div className="h2-board__track">
            <ul className="plain-list h2-board__list" aria-label={listLabel}>
              {rows.map((row) => <Row key={row.id} row={row} />)}
            </ul>
            {loop && (
              <ul className="plain-list h2-board__list" aria-hidden="true">
                {rows.map((row) => <Row key={row.id} row={row} copy />)}
              </ul>
            )}
          </div>
        </div>
      )}
      <p className="h2-board__foot">{foot}</p>
    </section>
  );
}

function Row({ row, copy = false }: { row: BoardRow; copy?: boolean }) {
  return (
    <li className="h2-board__row">
      <div className="h2-board__meta">
        {row.date && <time className="h2-board__date" dateTime={row.dateTime}>{row.date}</time>}
        {row.tag}
        {row.where && <span className="h2-board__where">{row.where}</span>}
      </div>
      {row.status ? <StatusPill tone={row.tone}>{row.status}</StatusPill> : <span />}
      <Link className="h2-board__name" to={row.href} tabIndex={copy ? -1 : undefined}>{row.title}</Link>
    </li>
  );
}
