import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { AI_ORIGIN } from '@/lib/aiMark';
import { DateFactRow } from './DateFactView';

export function DateTimeline({
  timeline,
  eventOrigin = null,
}: {
  timeline: ResultCard['timeline'];
  eventOrigin?: ResultCard['eventOrigin'];
}) {
  const { t } = useTranslation();
  const aiRead = eventOrigin === AI_ORIGIN.read;
  return (
    <div role="group" aria-label={t('card.timeline')}>
      <dl className="timeline">
        <DateFactRow label={t('date.published')} fact={timeline.published} />
        <DateFactRow label={t('date.updated')} fact={timeline.updated} />
        <DateFactRow label={t('date.discovered')} fact={timeline.discovered} />
        {timeline.eventStart && (
          <DateFactRow label={t('date.eventStart')} fact={timeline.eventStart} aiRead={aiRead} />
        )}
      </dl>
    </div>
  );
}
