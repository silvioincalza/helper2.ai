import { upTo } from '@/lib/breakpoints';
import { lazy, Suspense } from 'react';
import { useTranslation } from 'react-i18next';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useEventsFeed, useNewsFeed, type EventRow, type Feed, type NewsRow } from './homeFeeds';
import { useEventCells, useNewsCells } from './listParts';
import { TermList } from './TermList';

const PHONE_LISTS = upTo('tablet');
const ListTabs = lazy(() => import('./HomeTabs'));

export function HomeLists() {
  const phone = useMediaQuery(PHONE_LISTS, false);
  const news = useNewsFeed();
  const events = useEventsFeed();
  return phone ? (
    <Suspense fallback={null}>
      <ListTabs news={news} events={events} />
    </Suspense>
  ) : (
    <div className="h2-termlists">
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
      command="helper2 new"
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
      seeAll={{ to: '/events', label: t('lists.seeAllEvents') }}
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
