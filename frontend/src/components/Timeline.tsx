import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { DateFactRow } from './DateFactView';

/** Three distinct dates (plus the event date for events); each is its own DateFact. */
export function Timeline({ timeline }: { timeline: ResultCard['timeline'] }) {
  const { t } = useTranslation();
  return (
    <div role="group" aria-label={t('card.timeline')}>
      <dl className="timeline">
        <DateFactRow label={t('date.published')} fact={timeline.published} />
        <DateFactRow label={t('date.updated')} fact={timeline.updated} />
        <DateFactRow label={t('date.discovered')} fact={timeline.discovered} />
        {timeline.eventStart && <DateFactRow label={t('date.eventStart')} fact={timeline.eventStart} />}
      </dl>
    </div>
  );
}
