import { Fragment, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { EventRow, Feed, NewsRow } from './homeFeeds';
import { useEventCells, useNewsCells, type ListId } from './listParts';
import { TermList, type TermCells } from './TermList';

/** Phone: one panel with two tabs, `> new this week N | coming up N`, and TermList log rows (ADR 0042, decision 5).
 * Loaded only on a phone. */

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
  const newsCells = useNewsCells();
  const eventCells = useEventCells();
  const titles = { news: t('board.news.title'), events: t('board.events.title') };
  return (
    <div className="v4-tabs">
      <div role="tablist" aria-label={t('lists.tabs')} className="v4-tabs__list">
        {LISTS.map((id, i) => {
          const isSelected = id === selected;
          const feed = feeds[id];
          return (
            <Fragment key={id}>
              {i > 0 && (
                <span className="v4-tabs__sep" aria-hidden="true">
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
            </Fragment>
          );
        })}
      </div>
      <TabPanel
        id="news"
        selected={selected}
        feed={news}
        command="helper2 news"
        cells={newsCells}
      />
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
      className="v4-tabs__panel"
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
      />
    </div>
  );
}
