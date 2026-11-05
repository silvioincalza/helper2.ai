import { Link, useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { OriginLabel } from '@/components/Badges';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { displayHost, formatDate } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';
import { creationDate, formatMonth, groupByCreationMonth } from '@/lib/timeline';

/**
 * Search results on a time axis, newest first, by the date each item was CREATED as its source states it.
 * Helper2's discovery date (when we scanned it) is never used here; items with no stated creation date sit in
 * their own group at the end instead of being placed by a guess. Each month is a heading beside a line of dots; each
 * result its date, type, host, title and description. The title opens the resource page, which links back here.
 */
export function ResultsTimeline({ cards }: { cards: ResultCard[] }) {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const from = `${location.pathname}${location.search}`;
  return (
    <ol className="plain-list results-timeline" aria-label={t('timeline.label')}>
      {groupByCreationMonth(cards).map((group) => {
        const id = `tl-${group.month ?? 'unknown'}`;
        return (
          <li key={id} className="tl-group">
            <h3 id={id} className="tl-month">
              {group.month ? (
                <time dateTime={group.month}>{formatMonth(group.month, i18n.language)}</time>
              ) : (
                t('timeline.unknown')
              )}
            </h3>
            {!group.month && <p className="muted small">{t('timeline.unknownHint')}</p>}
            <ol className="plain-list tl-items" aria-labelledby={id}>
              {group.cards.map((card) => {
                const created = creationDate(card);
                const disputed = card.timeline.published.status === 'CONFLICTING';
                return (
                  <li key={card.id} className="tl-item">
                    <p className="tl-date">
                      {created ? (
                        <>
                          {t('timeline.created')}{' '}
                          <time dateTime={created}>
                            {formatDate(created, i18n.language, 'DAY')}
                          </time>
                          {disputed && <span className="muted"> ({t('date.conflicting')})</span>}
                        </>
                      ) : (
                        t('timeline.noDate')
                      )}
                    </p>
                    <p className="tl-meta">
                      {t(`type.${card.type}`)} · {displayHost(card.url)}
                    </p>
                    <Link to={resourcePath(card.id)} state={{ from }} className="tl-title">
                      {card.title}
                    </Link>
                    {card.description &&
                      (isAiOrigin(card.descriptionOrigin) ? (
                        <p className="tl-text">
                          <span className="h2-row__ai">
                            <OriginLabel origin={card.descriptionOrigin} />
                          </span>{' '}
                          <span {...aiMark()}>{card.description}</span>
                        </p>
                      ) : (
                        <p className="tl-text">{card.description}</p>
                      ))}
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
