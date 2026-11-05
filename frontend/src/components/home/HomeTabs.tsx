import { useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { EventRow, Feed, NewsRow } from './homeFeeds';
import {
  EventWhen,
  FIRST_ROWS,
  ListState,
  NewsDate,
  printIn,
  ShowAll,
  useEventText,
  useFirstRows,
  type ListId,
} from './listParts';

/** Phone: one panel with two tabs and log rows (ADR 0042, decision 5). Loaded only on a phone. */

const LISTS: readonly ListId[] = ['news', 'events'];

/** Arrow keys, Home and End move between the tabs and select them (WAI-ARIA tabs, automatic activation). */
function nextTab(key: string, current: ListId): ListId | null {
  const i = LISTS.indexOf(current);
  if (key === 'ArrowRight') return LISTS[(i + 1) % LISTS.length] as ListId;
  if (key === 'ArrowLeft') return LISTS[(i - 1 + LISTS.length) % LISTS.length] as ListId;
  if (key === 'Home') return LISTS[0] as ListId;
  if (key === 'End') return LISTS[LISTS.length - 1] as ListId;
  return null;
}

export default function ListTabs({
  news,
  events,
  initial,
}: {
  news: Feed<NewsRow>;
  events: Feed<EventRow>;
  initial: ListId;
}) {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<ListId>(initial);
  const [followed, setFollowed] = useState(initial);
  if (initial !== followed) {
    // A header link to the other list (/#events) selects its tab.
    setFollowed(initial);
    setSelected(initial);
  }
  const tabs = useRef<Partial<Record<ListId, HTMLButtonElement | null>>>({});
  const onKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    const next = nextTab(e.key, selected);
    if (!next) return;
    e.preventDefault();
    setSelected(next);
    tabs.current[next]?.focus();
  };
  const feeds = { news, events };
  const titles = { news: t('board.news.title'), events: t('board.events.title') };
  return (
    <div className="v4-tabs">
      <div role="tablist" aria-label={t('lists.tabs')} className="v4-tabs__list">
        {LISTS.map((id) => {
          const isSelected = id === selected;
          const feed = feeds[id];
          return (
            <button
              key={id}
              ref={(el) => {
                tabs.current[id] = el;
              }}
              type="button"
              role="tab"
              id={id}
              aria-selected={isSelected}
              aria-controls={`${id}-panel`}
              tabIndex={isSelected ? 0 : -1}
              className="v4-tab"
              onClick={() => setSelected(id)}
              onKeyDown={onKey}
            >
              <span className="v4-prompt" aria-hidden="true">
                {isSelected ? '> ' : ''}
              </span>
              {titles[id]}
              {!feed.loading && feed.rows.length > 0 && (
                <>
                  {' '}
                  <span className="v4-tab__total">{feed.total}</span>
                </>
              )}
            </button>
          );
        })}
      </div>
      <TabPanel id="news" selected={selected} feed={news} command="helper2 news">
        {(row) => <NewsLogRow row={row} />}
      </TabPanel>
      <TabPanel id="events" selected={selected} feed={events} command="helper2 events">
        {(row) => <EventLogRow row={row} />}
      </TabPanel>
    </div>
  );
}

function TabPanel<Row extends { id: string }>({
  id,
  selected,
  feed,
  command,
  children,
}: {
  id: ListId;
  selected: ListId;
  feed: Feed<Row>;
  command: string;
  children: (row: Row) => ReactNode;
}) {
  const { t } = useTranslation();
  const { all, toggle, shown } = useFirstRows(feed.rows);
  const listId = `${id}-log`;
  const texts =
    id === 'news'
      ? { error: t('radar.error'), empty: t('radar.empty') }
      : { error: t('events.error'), empty: t('events.empty') };
  return (
    <div
      role="tabpanel"
      id={`${id}-panel`}
      aria-labelledby={id}
      hidden={id !== selected}
      className="v4-tabs__panel"
    >
      <ListState feed={feed} errorText={texts.error} emptyText={texts.empty} />
      {feed.rows.length > 0 && (
        <>
          <ul id={listId} className="plain-list v4-log" aria-labelledby={id}>
            {shown.map((row, i) => (
              <li key={row.id} className="v4-row v4-log__row" style={printIn(i)}>
                {children(row)}
              </li>
            ))}
          </ul>
          {feed.total > FIRST_ROWS && (
            <ShowAll
              command={command}
              count={feed.total}
              all={all}
              controls={listId}
              onToggle={toggle}
            />
          )}
        </>
      )}
    </div>
  );
}

function NewsLogRow({ row }: { row: NewsRow }) {
  const { t } = useTranslation();
  return (
    <>
      <span className="v4-log__meta">
        <NewsDate row={row} /> <span className="v4-cell-type">[{t(`type.${row.type}`)}]</span>
      </span>
      <Link className="v4-log__title" to={row.href}>
        {row.title}
      </Link>
      {row.host && <span className="v4-log__where">{row.host}</span>}
    </>
  );
}

function EventLogRow({ row }: { row: EventRow }) {
  const text = useEventText();
  const kind = text.kind(row);
  const where = [text.left(row), text.where(row)].filter(Boolean).join(' · ');
  return (
    <>
      <span className="v4-log__meta">
        <EventWhen row={row} /> {kind && <span className="v4-cell-type">[{kind}]</span>}
      </span>
      <Link className="v4-log__title" to={row.href}>
        {row.title}
      </Link>
      {where && <span className="v4-log__where">{where}</span>}
    </>
  );
}
