import { lazy, Suspense } from 'react';
import { useTranslation } from 'react-i18next';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useEventsFeed, useNewsFeed, type EventRow, type Feed, type NewsRow } from './homeFeeds';
import { useEventCells, useNewsCells } from './listParts';
import { TermList } from './TermList';

/** Below this width the two tables become tabs (ADR 0042): a table with four columns does not fit a phone. */
const PHONE_LISTS = '(max-width: 767px)';
/** The tabs load only on a phone; the data is already on its way meanwhile. */
const ListTabs = lazy(() => import('./HomeTabs'));

/**
 * New this week and Coming up (ADR 0042, decision 5), both as one TermList. On a wide screen two tables side by side,
 * like `ls -l`: DATE · TYPE · TITLE · SOURCE and WHEN · TYPE · EVENT · WHERE. On a phone one panel with two tabs and
 * log rows. The
 * ids `news` and `events` are the targets of the header links (/#news, /#events).
 */
export function HomeLists() {
  const phone = useMediaQuery(PHONE_LISTS, false);
  const news = useNewsFeed();
  const events = useEventsFeed();
  return phone ? (
    <Suspense fallback={null}>
      <ListTabs news={news} events={events} />
    </Suspense>
  ) : (
    <div className="v4-lists">
      <NewsTable feed={news} />
      <EventsTable feed={events} />
    </div>
  );
}

function NewsTable({ feed }: { feed: Feed<NewsRow> }) {
  const { t } = useTranslation();
  return (
    <TermList
      id="news"
      layout="table"
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
      cells={useNewsCells()}
    />
  );
}

function EventsTable({ feed }: { feed: Feed<EventRow> }) {
  const { t } = useTranslation();
  return (
    <TermList
      id="events"
      layout="table"
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
      cells={useEventCells()}
    />
  );
}
