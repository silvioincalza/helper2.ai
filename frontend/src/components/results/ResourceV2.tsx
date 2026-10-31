import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResourceDetail } from '@/api/types';
import { BookmarkButton } from '@/components/BookmarkButton';
import { canGuide } from '@/components/GuideMe';
import { SetupGuidePanel } from '@/components/SetupGuide';
import { Timeline } from '@/components/Timeline';
import { ProofQuote } from '@/components/ui/ProofQuote';
import { TypeTag } from '@/components/ui/TypeTag';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { SAVED_ENABLED } from '@/lib/features';
import { displayHost } from '@/lib/selection';
import { ListedBy, ResultFacts } from './ResultFacts';
import './results-v2.css';
import './resource-v2.css';

/** What the results page passes on: where to go back to, and the sentence the guide checked. */
interface FromResults { from?: string | undefined; quote?: string | null }

function fromResults(state: unknown): FromResults {
  if (!state || typeof state !== 'object') return {};
  const s = state as Record<string, unknown>;
  // Only a path inside the site, so the back link can never leave it.
  const from = typeof s.from === 'string' && s.from.startsWith('/') && !s.from.startsWith('//') ? s.from : undefined;
  const quote = typeof s.quote === 'string' && s.quote.trim() ? s.quote : null;
  return { from, quote };
}

/**
 * Design v2 resource page (ADR 0040, 4): the same parts as the results preview, full width. The main column has the
 * title, the actions, the source's own text (with the checked sentence when the guide picked it) and the detail
 * sections ([children]); the side column has the Use and Build facts, who lists it and its dates.
 */
export function ResourceV2({ detail: d, children }: { detail: ResourceDetail; children: ReactNode }) {
  const { t } = useTranslation();
  const { from, quote } = fromResults(useLocation().state);
  const card = d.card;
  const host = displayHost(card.url);
  return (
    <div className="h2-res">
      <p className="h2-res__back">
        <Link to={from ?? '/'}><span aria-hidden="true">← </span>{from ? t('resourceV2.backToResults') : t('detail.backToSearch')}</Link>
      </p>
      <div className="h2-res__grid">
        <div className="h2-res__main">
          <div className="h2-pv__top"><TypeTag type={card.type} /><span className="h2-row__host">{host}</span></div>
          <h1 className="h2-pv__title">{card.title}</h1>
          <div className="h2-pv__actions">
            <a className="btn btn-primary" href={card.url} target="_blank" rel="noopener noreferrer">
              {t('resultsV2.openOn', { host })} <span aria-hidden="true">↗</span>
            </a>
            {SAVED_ENABLED && <BookmarkButton card={card} />}
          </div>
          {canGuide(card) && <SetupGuidePanel id={card.id} title={card.title} />}

          <section className="h2-res__sec" aria-labelledby="h2-res-text">
            <h2 id="h2-res-text" className="h2-pv__sec">{t('resourceV2.sourceText')}</h2>
            {/* A checked quote can come from the title alone, so it shows even without a description. */}
            {quote ? (
              <ProofQuote text={card.description ?? ''} quote={quote} source={host} />
            ) : card.description ? (
              <p className="h2-res__text" {...(isAiOrigin(card.descriptionOrigin) ? aiMark() : {})}>{card.description}</p>
            ) : (
              <p className="muted">{t('card.noDescription')}</p>
            )}
            {quote && <p className="muted small">{t('resourceV2.checkedNote')}</p>}
          </section>

          <div className="h2-res__details">{children}</div>
        </div>

        <aside className="h2-res__side" aria-label={t('resultsV2.facts')}>
          <section aria-labelledby="h2-res-facts">
            <h2 id="h2-res-facts" className="h2-pv__sec">{t('resultsV2.facts')}</h2>
            <ResultFacts card={card} groupTag="h3" />
          </section>
          <section aria-labelledby="h2-res-listed">
            <h2 id="h2-res-listed" className="h2-pv__sec">{t('resultsV2.listedBy')}</h2>
            <ListedBy card={card} />
          </section>
          <section aria-labelledby="h2-res-time">
            <h2 id="h2-res-time" className="h2-pv__sec">{t('resultsV2.timeline')}</h2>
            <Timeline timeline={card.timeline} />
          </section>
        </aside>
      </div>
    </div>
  );
}
