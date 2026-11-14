import { useId, useState } from 'react';
import { Link, useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { OriginLabel } from '@/ui/Badges';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { displayHost, formatDate, formatMonth } from '@/lib/format';
import { plainText } from '@/lib/plainText';
import { resourcePath } from '@/lib/searchState';
import { creationDate, groupByCreationMonth } from '@/lib/timeline';

export function ResultsTimeline({ cards }: { cards: ResultCard[] }) {
  const { t } = useTranslation();
  const location = useLocation();
  const from = `${location.pathname}${location.search}`;
  const [unknownOpen, setUnknownOpen] = useState(false);
  const unknownList = useId();
  return (
    <ol className="plain-list results-timeline" aria-label={t('timeline.label')}>
      {groupByCreationMonth(cards).map((group) => {
        const id = `tl-${group.month ?? 'unknown'}`;
        return (
          <li key={id} className="tl-group">
            <h3 id={id} className="tl-month">
              {group.month ? (
                <time dateTime={group.month}>{formatMonth(group.month)}</time>
              ) : (
                t('timeline.unknown')
              )}
            </h3>
            {!group.month && (
              <p className="tl-unknown">
                {t('timeline.unknownCount', { count: group.cards.length })}{' '}
                {t('timeline.unknownHint')}{' '}
                <button
                  type="button"
                  className="btn-text"
                  aria-expanded={unknownOpen}
                  aria-controls={unknownList}
                  onClick={() => setUnknownOpen((o) => !o)}
                >
                  {t(unknownOpen ? 'timeline.hideThem' : 'timeline.showThem')}
                </button>
              </p>
            )}
            <ol
              id={group.month ? undefined : unknownList}
              hidden={!group.month && !unknownOpen}
              className="plain-list tl-items"
              aria-labelledby={id}
            >
              {group.cards.map((card) => {
                const created = creationDate(card);
                const disputed = card.timeline.published.status === 'CONFLICTING';
                return (
                  <li key={card.id} className="tl-item" data-id={card.id}>
                    <p className="tl-date">
                      {created ? (
                        <>
                          {t('timeline.created')}{' '}
                          <time dateTime={created}>{formatDate(created, 'DAY')}</time>
                          {disputed && <span className="muted"> ({t('date.conflicting')})</span>}
                        </>
                      ) : (
                        t('timeline.noDate')
                      )}
                    </p>
                    <p className="tl-meta">
                      {t(`type.${card.type}`)} · {displayHost(card.url)}
                    </p>
                    <Link
                      to={resourcePath(card.id, card.title)}
                      state={{ from }}
                      className="tl-title"
                    >
                      {card.title}
                    </Link>
                    {card.description &&
                      (isAiOrigin(card.descriptionOrigin) ? (
                        <p className="tl-text">
                          <OriginLabel origin={card.descriptionOrigin} />{' '}
                          <span {...aiMark()}>{plainText(card.description)}</span>
                        </p>
                      ) : (
                        <p className="tl-text">{plainText(card.description)}</p>
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
