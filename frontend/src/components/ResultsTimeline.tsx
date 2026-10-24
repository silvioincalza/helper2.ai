import { useTranslation } from 'react-i18next';
import type { ResultCard, SourceStatus } from '@/api/types';
import { formatDate } from '@/lib/format';
import { creationDate, formatMonth, groupByCreationMonth } from '@/lib/timeline';
import { ResultCardView } from './ResultCardView';

/**
 * Search results on a time axis, newest first, by the date each item was CREATED as its source states it.
 * Helper2's discovery date (when we scanned it) is never used here; items with no stated creation date sit in
 * their own group at the end instead of being placed by a guess.
 */
export function ResultsTimeline({ cards, sourceStatuses }: { cards: ResultCard[]; sourceStatuses: SourceStatus[] }) {
  const { t, i18n } = useTranslation();
  return (
    <ol className="plain-list results-timeline" aria-label={t('timeline.label')}>
      {groupByCreationMonth(cards).map((group) => {
        const id = `tl-${group.month ?? 'unknown'}`;
        return (
          <li key={id} className="tl-group">
            <h3 id={id} className="tl-month">
              {group.month ? <time dateTime={group.month}>{formatMonth(group.month, i18n.language)}</time> : t('timeline.unknown')}
            </h3>
            {!group.month && <p className="muted small">{t('timeline.unknownHint')}</p>}
            <ol className="plain-list tl-items" aria-labelledby={id}>
              {group.cards.map((card) => {
                const created = creationDate(card);
                const disputed = card.timeline.published.status === 'CONFLICTING';
                return (
                  <li key={card.id} className="tl-item">
                    <p className="tl-date small">
                      {created ? (
                        <>
                          {t('timeline.created')} <time dateTime={created}>{formatDate(created, i18n.language, 'DAY')}</time>
                          {disputed && <span className="muted"> ({t('date.conflicting')})</span>}
                        </>
                      ) : t('timeline.noDate')}
                    </p>
                    <ResultCardView card={card} sourceStatuses={sourceStatuses} />
                  </li>
                );
              })}
            </ol>
          </li>
        );
      })}
    </ol>
  );
}
