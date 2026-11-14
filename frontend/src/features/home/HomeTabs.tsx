import { Fragment, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router';
import type { EventRow, Feed, NewsRow } from './homeFeeds';
import { useEventCells, useNewsCells, type ListId } from './listParts';
import { TermList, type TermCells } from './TermList';

const LISTS: readonly ListId[] = ['news', 'events'];

function listOf(hash: string): ListId | null {
  const id = hash.slice(1);
  return LISTS.find((l) => l === id) ?? null;
}

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
}: {
  news: Feed<NewsRow>;
  events: Feed<EventRow>;
}) {
  const { t } = useTranslation();
  const { hash, key } = useLocation();
  const linked = listOf(hash);
  const [selected, setSelected] = useState<ListId>(linked ?? 'news');
  const [followed, setFollowed] = useState(key);
  if (key !== followed) {
    setFollowed(key);
    if (linked) setSelected(linked);
  }
  const opened = useRef(linked);
  useEffect(() => {
    if (opened.current) document.getElementById(opened.current)?.scrollIntoView({ block: 'start' });
  }, []);
  const tabs = useRef<Partial<Record<ListId, HTMLButtonElement | null>>>({});
  const onKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    const next = nextTab(e.key, selected);
    if (!next) return;
    e.preventDefault();
    setSelected(next);
    tabs.current[next]?.focus();
  };
  const feeds = { news, events };
  const newsCells = useNewsCells();
  const eventCells = useEventCells();
  const titles = { news: t('board.news.title'), events: t('board.events.title') };
  return (
    <div className="h2-tabs">
      <div role="tablist" aria-label={t('lists.tabs')} className="h2-tabs__list">
        {LISTS.map((id, i) => {
          const isSelected = id === selected;
          const feed = feeds[id];
          return (
            <Fragment key={id}>
              {i > 0 && (
                <span className="h2-tabs__sep" aria-hidden="true">
                  |
                </span>
              )}
              <button
                ref={(el) => {
                  tabs.current[id] = el;
                }}
                type="button"
                role="tab"
                id={id}
                aria-selected={isSelected}
                aria-controls={`${id}-panel`}
                tabIndex={isSelected ? 0 : -1}
                className="h2-tab"
                onClick={() => setSelected(id)}
                onKeyDown={onKey}
              >
                <span className="h2-prompt" aria-hidden="true">
                  {isSelected ? '> ' : ''}
                </span>
                {titles[id]}
                {!feed.loading && feed.rows.length > 0 && (
                  <>
                    {' '}
                    <span className="h2-tab__total">{feed.total}</span>
                  </>
                )}
              </button>
            </Fragment>
          );
        })}
      </div>
      <TabPanel id="news" selected={selected} feed={news} command="helper2 new" cells={newsCells} />
      <TabPanel
        id="events"
        selected={selected}
        feed={events}
        command="helper2 events"
        cells={eventCells}
      />
    </div>
  );
}

function TabPanel<Row extends { id: string }>({
  id,
  selected,
  feed,
  command,
  cells,
}: {
  id: ListId;
  selected: ListId;
  feed: Feed<Row>;
  command: string;
  cells: (row: Row, now: number) => TermCells;
}) {
  const { t } = useTranslation();
  const texts =
    id === 'news'
      ? { title: t('board.news.title'), error: t('radar.error'), empty: t('radar.empty') }
      : { title: t('board.events.title'), error: t('events.error'), empty: t('events.empty') };
  return (
    <div
      role="tabpanel"
      id={`${id}-panel`}
      aria-labelledby={id}
      hidden={id !== selected}
      className="h2-tabs__panel"
    >
      <TermList
        id={id}
        layout="log"
        title={texts.title}
        command={command}
        feed={feed}
        errorText={texts.error}
        emptyText={texts.empty}
        columns={[]}
        cells={cells}
        {...(id === 'events' ? { seeAll: { to: '/events', label: t('lists.seeAllEvents') } } : {})}
      />
    </div>
  );
}
