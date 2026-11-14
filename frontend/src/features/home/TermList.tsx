import { useRef, type CSSProperties, type ReactNode } from 'react';
import { Link } from 'react-router';
import type { Feed } from './homeFeeds';
import { FIRST_ROWS, ListState, ShowAll, useFirstRows, type ListId } from './listParts';
import { tickerWindow, useTicker } from './ticker';
import { OriginLabel } from '@/ui/Badges';
import { aiMark } from '@/lib/aiMark';
import { useFromHere } from '@/features/search/fromResults';

export interface TermCells {
  when: ReactNode;
  type: string;
  title: string;
  href: string;
  where: string;
  note?: string;
  ai?: boolean;
}

function Ai({ ai, children }: { ai?: boolean | undefined; children: ReactNode }) {
  return ai ? <span {...aiMark()}>{children}</span> : <>{children}</>;
}

function AiExtracted({ ai }: { ai?: boolean | undefined }) {
  return ai ? <OriginLabel origin="AI_EXTRACTED" /> : null;
}

export function TermList<Row extends { id: string }>({
  id,
  layout,
  title,
  command,
  feed,
  errorText,
  emptyText,
  columns,
  cells,
  seeAll,
}: {
  id: ListId;
  layout: 'table' | 'log';
  title: string;
  command: string;
  feed: Feed<Row>;
  errorText: string;
  emptyText: string;
  columns: string[];
  cells: (row: Row, now: number) => TermCells;
  seeAll?: { to: string; label: string };
}) {
  const { all, toggle, shown: first } = useFirstRows(feed.rows);
  const box = useRef<HTMLDivElement>(null);
  const ticker = useTicker(box, !all && feed.rows.length > FIRST_ROWS, {
    holdOnScroll: layout === 'log',
  });
  const shown = ticker.offset > 0 ? tickerWindow(feed.rows, ticker.offset, FIRST_ROWS) : first;
  const slide = ticker.offset > 0 ? 'is-sliding' : undefined;
  const slideStyle = { '--tick-step': `${ticker.step}px` } as CSSProperties;
  const listId = `${id}-${layout}`;
  const labelledBy = layout === 'table' ? `${id}-h` : id;
  const body =
    layout === 'table' ? (
      <table id={listId} className="h2-table" aria-labelledby={labelledBy}>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c} scope="col">
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody key={ticker.offset} className={slide} style={slideStyle}>
          {shown.map((row) => (
            <TableRow key={row.id} cells={cells(row, feed.now)} />
          ))}
        </tbody>
      </table>
    ) : (
      <ul
        key={ticker.offset}
        id={listId}
        className={slide ? `plain-list h2-log ${slide}` : 'plain-list h2-log'}
        style={slideStyle}
        aria-labelledby={labelledBy}
      >
        {shown.map((row) => (
          <LogRow key={row.id} cells={cells(row, feed.now)} />
        ))}
      </ul>
    );
  const list = (
    <>
      <ListState feed={feed} errorText={errorText} emptyText={emptyText} />
      {feed.rows.length > 0 && (
        <>
          <div ref={box} className="h2-ticker" {...ticker.handlers}>
            {body}
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
  if (layout === 'log')
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

export function TermCommand({ command, total }: { command: string; total: number | null }) {
  return (
    <p className="h2-termlist__cmd">
      <span aria-hidden="true">
        <span className="h2-prompt">$</span> {command}
        {total !== null && ' · '}
      </span>
      {total !== null && <span className="h2-termlist__total">{total}</span>}
    </p>
  );
}

function TableRow({ cells }: { cells: TermCells }) {
  const from = useFromHere();
  return (
    <tr className="h2-term-row">
      <td className="h2-cell-date">
        <Ai ai={cells.ai}>{cells.when}</Ai>
        {cells.note && <span className="h2-cell-note">{cells.note}</span>}
        <AiExtracted ai={cells.ai} />
      </td>
      <td className="h2-cell-type">
        <Ai ai={cells.ai}>{cells.type}</Ai>
      </td>
      <td className="h2-cell-title">
        <Link to={cells.href} state={from}>
          {cells.title}
        </Link>
      </td>
      <td className="h2-cell-meta">
        <Ai ai={cells.ai}>{cells.where}</Ai>
      </td>
    </tr>
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
