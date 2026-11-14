import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { DateFactRow } from './DateFactView';

export function DateTimeline({
  timeline,
  compact = false,
  eventOrigin = null,
}: {
  timeline: ResultCard['timeline'];
  compact?: boolean;
  eventOrigin?: ResultCard['eventOrigin'];
}) {
  const { t } = useTranslation();
  const aiRead = eventOrigin === 'AI_EXTRACTED';
  if (compact) {
    return (
      <div role="group" aria-label={t('card.timeline')}>
        <dl className="timeline timeline-compact">
          <DateFactRow label={t('date.updated')} fact={timeline.updated} />
          {timeline.eventStart && (
            <DateFactRow label={t('date.eventStart')} fact={timeline.eventStart} aiRead={aiRead} />
          )}
        </dl>
      </div>
    );
  }
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
