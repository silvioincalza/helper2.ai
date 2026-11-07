import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { DateFactRow } from './DateFactView';

/**
 * Three distinct dates (plus the event date for events); each is its own DateFact.
 * Compact (result cards): only "last updated", the date people scan for; the detail page shows all.
 */
export function Timeline({
  timeline,
  compact = false,
  eventOrigin = null,
}: {
  timeline: ResultCard['timeline'];
  compact?: boolean;
  /** AI_EXTRACTED: an AI model read the event's days on its page (ADR 0067); they carry the AI label. */
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
