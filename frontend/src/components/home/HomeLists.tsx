import { lazy, Suspense, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useEventsFeed, useNewsFeed, type EventRow, type Feed, type NewsRow } from './homeFeeds';
import {
  EventWhen,
  FIRST_ROWS,
  ListHead,
  ListState,
  NewsDate,
  printIn,
  ShowAll,
  useEventText,
  useFirstRows,
  type ListId,
} from './listParts';

/** Below this width the two tables become tabs (ADR 0042): a table with four columns does not fit a phone. */
export const PHONE_LISTS = '(max-width: 767px)';
/** The tabs load only on a phone; the data is already on its way meanwhile. */
const ListTabs = lazy(() => import('./HomeTabs'));

/**
 * New this week and Coming up (ADR 0042, decision 4). On a wide screen two tables side by side, like `ls -l`:
 * DATE · TYPE · TITLE · SOURCE and WHEN · TYPE · EVENT · WHERE. On a phone one panel with two tabs and log rows. The
 * ids `news` and `events` are the targets of the header links (/#news, /#events).
 */
export function HomeLists({ initial }: { initial: ListId }) {
  const phone = useMediaQuery(PHONE_LISTS, false);
  const news = useNewsFeed();
  const events = useEventsFeed();
  return phone ? (
    <Suspense fallback={null}>
      <ListTabs news={news} events={events} initial={initial} />
    </Suspense>
  ) : (
    <div className="v4-lists">
      <NewsTable feed={news} />
      <EventsTable feed={events} />
    </div>
  );
}

function ListTable({
  id,
  title,
  command,
  feed,
  errorText,
  emptyText,
  columns,
  row,
}: {
  id: ListId;
  title: string;
  command: string;
  feed: Feed<{ id: string }>;
  errorText: string;
  emptyText: string;
  columns: string[];
  row: (index: number) => ReactNode;
}) {
  const { all, toggle, shown } = useFirstRows(feed.rows);
  const tableId = `${id}-table`;
  return (
    <section id={id} className="v4-list" aria-labelledby={`${id}-h`}>
      <ListHead id={id} title={title} total={feed.rows.length > 0 ? feed.total : null} />
      <ListState feed={feed} errorText={errorText} emptyText={emptyText} />
      {feed.rows.length > 0 && (
        <>
          <table id={tableId} className="v4-table" aria-labelledby={`${id}-h`}>
            <thead>
              <tr>
                {columns.map((c) => (
                  <th key={c} scope="col">
                    {c}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>{shown.map((_, i) => row(i))}</tbody>
          </table>
          {feed.total > FIRST_ROWS && (
            <ShowAll
              command={command}
              count={feed.total}
              all={all}
              controls={tableId}
              onToggle={toggle}
            />
          )}
        </>
      )}
    </section>
  );
}

function NewsTable({ feed }: { feed: Feed<NewsRow> }) {
  const { t } = useTranslation();
  return (
    <ListTable
      id="news"
      title={t('board.news.title')}
      command="helper2 news"
      feed={feed}
      errorText={t('radar.error')}
      emptyText={t('radar.empty')}
      columns={[
        t('lists.col.date'),
        t('lists.col.type'),
        t('lists.col.title'),
        t('lists.col.source'),
      ]}
      row={(i) => {
        const row = feed.rows[i] as NewsRow;
        return (
          <tr key={row.id} className="v4-row" style={printIn(i)}>
            <td className="v4-cell-date">
              <NewsDate row={row} />
            </td>
            <td className="v4-cell-type">{t(`type.${row.type}`)}</td>
            <td className="v4-cell-title">
              <Link to={row.href}>{row.title}</Link>
            </td>
            <td className="v4-cell-meta">{row.host}</td>
          </tr>
        );
      }}
    />
  );
}

function EventsTable({ feed }: { feed: Feed<EventRow> }) {
  const { t } = useTranslation();
  const text = useEventText();
  return (
    <ListTable
      id="events"
      title={t('board.events.title')}
      command="helper2 events"
      feed={feed}
      errorText={t('events.error')}
      emptyText={t('events.empty')}
      columns={[
        t('lists.col.when'),
        t('lists.col.type'),
        t('lists.col.event'),
        t('lists.col.where'),
      ]}
      row={(i) => {
        const row = feed.rows[i] as EventRow;
        return (
          <tr key={row.id} className="v4-row" style={printIn(i)}>
            <td className="v4-cell-date">
              <EventWhen row={row} />
            </td>
            <td className="v4-cell-type">{text.kind(row)}</td>
            <td className="v4-cell-title">
              <Link to={row.href}>{row.title}</Link>
            </td>
            <td className="v4-cell-meta">{text.where(row)}</td>
          </tr>
        );
      }}
    />
  );
}
