import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import type { Feed } from './homeFeeds';
import { FIRST_ROWS, ListState, ShowAll, useFirstRows, type ListId } from './listParts';

/** One row as both layouts show it: the date or when, the type, the title and where it comes from. */
export interface TermCells {
  when: ReactNode;
  type: string;
  title: string;
  href: string;
  where: string;
  /** How soon, e.g. "In 2 days": under the date in a table, before `where` in a log row. */
  note?: string;
}

/**
 * New this week and Coming up share this one list (ADR 0042, decision 5), in the terminal style: a
 * `$ helper2 news · N` line, `ls -l` columns with dashed rules, titles in Geist and the rest in Geist Mono. `table` on
 * a wide screen (four columns with headers), `log` on a phone (inside a tab: the `$` line, then one row per item). Loading,
 * empty and error come from ListState. Nothing in it moves.
 */
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
}) {
  const { t } = useTranslation();
  const { all, toggle, shown } = useFirstRows(feed.rows);
  // The API counts more than it sends (its size cap, or records that no longer merge): say so once all are open.
  const notSent = feed.total - feed.rows.length;
  const showsAll = all || feed.rows.length <= FIRST_ROWS;
  const listId = `${id}-${layout}`;
  const labelledBy = layout === 'table' ? `${id}-h` : id;
  const body =
    layout === 'table' ? (
      <table id={listId} className="v4-table" aria-labelledby={labelledBy}>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c} scope="col">
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {shown.map((row) => (
            <TableRow key={row.id} cells={cells(row, feed.now)} />
          ))}
        </tbody>
      </table>
    ) : (
      <ul id={listId} className="plain-list v4-log" aria-labelledby={labelledBy}>
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
          {body}
          {feed.rows.length > FIRST_ROWS && (
            <ShowAll
              command={command}
              count={feed.rows.length}
              all={all}
              controls={listId}
              onToggle={toggle}
            />
          )}
          {showsAll && notSent > 0 && (
            <p className="v4-list__more">{t('lists.more', { count: notSent })}</p>
          )}
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
    <section id={id} className="v4-list" aria-labelledby={`${id}-h`}>
      <TermHead id={id} title={title} command={command} total={total} />
      {list}
    </section>
  );
}

/** "New this week" over the line `$ helper2 news · 38`. */
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
    <div className="v4-list__head">
      <h2 id={`${id}-h`} className="v4-list__title">
        {title}
      </h2>
      <TermCommand command={command} total={total} />
    </div>
  );
}

/** `$ helper2 news · 38`, with 38 the list's `total` from the API (none while it loads or when it is empty). */
function TermCommand({ command, total }: { command: string; total: number | null }) {
  return (
    <p className="v4-list__cmd">
      <span aria-hidden="true">
        <span className="v4-prompt">$</span> {command}
        {total !== null && ' · '}
      </span>
      {total !== null && <span className="v4-list__total">{total}</span>}
    </p>
  );
}

function TableRow({ cells }: { cells: TermCells }) {
  return (
    <tr className="v4-row">
      <td className="v4-cell-date">
        {cells.when}
        {cells.note && <span className="v4-cell-note">{cells.note}</span>}
      </td>
      <td className="v4-cell-type">{cells.type}</td>
      <td className="v4-cell-title">
        <Link to={cells.href}>{cells.title}</Link>
      </td>
      <td className="v4-cell-meta">{cells.where}</td>
    </tr>
  );
}

function LogRow({ cells }: { cells: TermCells }) {
  const where = [cells.note, cells.where].filter(Boolean).join(' · ');
  return (
    <li className="v4-row v4-log__row">
      <span className="v4-log__meta">
        {cells.when} {cells.type && <span className="v4-cell-type">[{cells.type}]</span>}
      </span>
      <Link className="v4-log__title" to={cells.href}>
        {cells.title}
      </Link>
      {where && <span className="v4-log__where">{where}</span>}
    </li>
  );
}
