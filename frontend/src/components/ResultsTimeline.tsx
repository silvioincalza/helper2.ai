import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { OriginLabel } from '@/components/Badges';
import { TypeTag } from '@/components/ui/TypeTag';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { formatDate } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';
import { displayHost } from '@/lib/selection';
import { creationDate, formatMonth, groupByCreationMonth } from '@/lib/timeline';

/**
 * Search results on a time axis, newest first, by the date each item was CREATED as its source states it.
 * Helper2's discovery date (when we scanned it) is never used here; items with no stated creation date sit in
 * their own group at the end instead of being placed by a guess. One line per result, as in the list; the
 * resource page has the rest.
 */
export function ResultsTimeline({ cards }: { cards: ResultCard[] }) {
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
                    <Link to={resourcePath(card.id)} className="h2-row tl-row">
                      <span className="h2-row__meta">
                        <TypeTag type={card.type} />
                        <span className="h2-row__host">{displayHost(card.url)}</span>
                      </span>
                      <span className="h2-row__title">{card.title}</span>
                      {card.description && (
                        isAiOrigin(card.descriptionOrigin)
                          ? <span className="h2-row__text"><span className="h2-row__ai"><OriginLabel origin={card.descriptionOrigin} /></span> <span {...aiMark()}>{card.description}</span></span>
                          : <span className="h2-row__text">{card.description}</span>
                      )}
                    </Link>
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
