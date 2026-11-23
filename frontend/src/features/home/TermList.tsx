import { useRef, type CSSProperties, type ReactNode } from 'react';
import { Link } from 'react-router';
import type { Feed } from '@/features/feeds/feeds';
import type { TermCells } from '@/features/feeds/feedCells';
import { ShowAll, useFirstRows, type ListId } from './listParts';
import { FIRST_ROWS, ListState } from '@/ui/ListState';
import { TermCommand } from '@/ui/TermCommand';
import { tickerWindow, useTicker } from './ticker';
import { OriginLabel } from '@/ui/Badges';
import { AI_ORIGIN, aiMark } from '@/lib/aiMark';
import { useFromHere } from '@/lib/fromResults';

function Ai({ ai, children }: { ai?: boolean | undefined; children: ReactNode }) {
  return ai ? <span {...aiMark()}>{children}</span> : <>{children}</>;
}

function AiExtracted({ ai }: { ai?: boolean | undefined }) {
  return ai ? <OriginLabel origin={AI_ORIGIN.read} /> : null;
}

/**
 * One home list as log rows. In a column (wide screens) it is its own section with a heading;
 * in a tab (phones) the tab is its name.
 */
export function TermList<Row extends { id: string }>({
  id,
  inTab = false,
  title,
  command,
  feed,
  errorText,
  emptyText,
  cells,
  seeAll,
}: {
  id: ListId;
  inTab?: boolean;
  title: string;
  command: string;
  feed: Feed<Row>;
  errorText: string;
  emptyText: string;
  cells: (row: Row, now: number) => TermCells;
  seeAll?: { to: string; label: string };
}) {
  const { all, toggle, shown: first } = useFirstRows(feed.rows);
  const box = useRef<HTMLDivElement>(null);
  const ticker = useTicker(box, !all && feed.rows.length > FIRST_ROWS, {
    holdOnScroll: inTab,
  });
  const shown = ticker.offset > 0 ? tickerWindow(feed.rows, ticker.offset, FIRST_ROWS) : first;
  const slide = ticker.offset > 0 ? 'is-sliding' : undefined;
  const slideStyle = { '--tick-step': `${ticker.step}px` } as CSSProperties;
  const listId = `${id}-log`;
  const list = (
    <>
      <ListState feed={feed} errorText={errorText} emptyText={emptyText} />
      {feed.rows.length > 0 && (
        <>
          <div ref={box} className="h2-ticker" {...ticker.handlers}>
            <ul
              key={ticker.offset}
              id={listId}
              className={slide ? `plain-list h2-log ${slide}` : 'plain-list h2-log'}
              style={slideStyle}
              aria-labelledby={inTab ? id : `${id}-h`}
            >
              {shown.map((row) => (
                <LogRow key={row.id} cells={cells(row, feed.now)} />
              ))}
            </ul>
          </div>
          <div className="h2-termlist__foot">
            {feed.rows.length > FIRST_ROWS && (
              <ShowAll
                command={command}
                count={feed.rows.length}
                all={all}
                controls={listId}
                onToggle={toggle}
              />
            )}
            {seeAll && (
              <Link className="h2-termlist__all" to={seeAll.to}>
                {seeAll.label}
              </Link>
            )}
          </div>
        </>
      )}
    </>
  );
  const total = feed.rows.length > 0 ? feed.total : null;
  if (inTab)
    return (
      <>
        <TermCommand command={command} total={total} />
        {list}
      </>
    );
  return (
    <section id={id} className="h2-termlist" aria-labelledby={`${id}-h`}>
      <TermHead id={id} title={title} command={command} total={total} />
      {list}
    </section>
  );
}

function TermHead({
  id,
  title,
  command,
  total,
}: {
  id: ListId;
  title: string;
  command: string;
  total: number | null;
}) {
  return (
    <div className="h2-termlist__head">
      <h2 id={`${id}-h`} className="h2-termlist__title">
        {title}
      </h2>
      <TermCommand command={command} total={total} />
    </div>
  );
}

function LogRow({ cells }: { cells: TermCells }) {
  const from = useFromHere();
  const where = [cells.note, cells.where].filter(Boolean).join(' · ');
  return (
    <li className="h2-term-row h2-log__row">
      <span className="h2-log__meta">
        <Ai ai={cells.ai}>{cells.when}</Ai>{' '}
        {cells.type && (
          <span className="h2-cell-type">
            [<Ai ai={cells.ai}>{cells.type}</Ai>]
          </span>
        )}{' '}
        <AiExtracted ai={cells.ai} />
      </span>
      <Link className="h2-log__title" to={cells.href} state={from}>
        {cells.title}
      </Link>
      {where && (
        <span className="meta-line">
          <Ai ai={cells.ai}>{where}</Ai>
        </span>
      )}
    </li>
  );
}
