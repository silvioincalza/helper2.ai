import { upTo } from '@/lib/breakpoints';
import { lazy, Suspense } from 'react';
import { useTranslation } from 'react-i18next';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import {
  useEventsFeed,
  useNewsFeed,
  type EventRow,
  type Feed,
  type NewsRow,
} from '@/features/feeds/feeds';
import { useEventCells, useNewsCells } from '@/features/feeds/feedCells';
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
      <NewsList feed={news} />
      <EventsList feed={events} />
    </div>
  );
}

function NewsList({ feed }: { feed: Feed<NewsRow> }) {
  const { t } = useTranslation();
  return (
    <TermList
      id="news"
      title={t('board.news.title')}
      command="helper2 new"
      feed={feed}
      errorText={t('radar.error')}
      emptyText={t('radar.empty')}
      cells={useNewsCells()}
    />
  );
}

function EventsList({ feed }: { feed: Feed<EventRow> }) {
  const { t } = useTranslation();
  return (
    <TermList
      id="events"
      title={t('board.events.title')}
      command="helper2 events"
      feed={feed}
      errorText={t('events.error')}
      emptyText={t('events.empty')}
      seeAll={{ to: '/events', label: t('lists.seeAllEvents') }}
      cells={useEventCells()}
    />
  );
}
