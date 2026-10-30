import { useId, useRef } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResultCard, SourceStatus } from '@/api/types';
import { ProofQuote } from '@/components/ui/ProofQuote';
import { TypeTag } from '@/components/ui/TypeTag';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { SAVED_ENABLED } from '@/lib/features';
import { hostname } from '@/lib/format';
import { FreshnessBadge, ModeBadge, OriginLabel } from './Badges';
import { BookmarkButton } from './BookmarkButton';
import { FactList, WorksWith } from './CardFacts';
import { ExternalLink } from './ExternalLink';
import type { GuidePick } from './GuidePicks';
import { canGuide } from './GuideMe';
import { Icon } from './Icon';
import { Popularity } from './Popularity';
import { GuideBody, GuideMeButton, useGuideToggle } from './SetupGuide';
import { Timeline } from './Timeline';

/** The sources that listed [card], in words, for the proof quote's caption. */
const listedBy = (card: ResultCard) => card.sources.map((s) => s.sourceName).join(', ');

/**
 * Why the result matches: for a guide pick, the source's text with the sentence the guide checked highlighted, the
 * user's requirements checked against it, and the model's explanation labelled with the outcome of its check; for
 * any other result, its description and where that came from.
 */
function WhyItMatches({ card, pick }: { card: ResultCard; pick: GuidePick | undefined }) {
  const { t } = useTranslation();
  if (!pick) {
    return card.description ? (
      <>
        <p className="preview-text" {...(isAiOrigin(card.descriptionOrigin) ? aiMark() : {})}>{card.description}</p>
        {card.descriptionOrigin !== 'SOURCE_PROVIDED' && (
          <p className="muted small">{t('card.descriptionOrigin')}: <OriginLabel origin={card.descriptionOrigin} /></p>
        )}
      </>
    ) : <p className="muted">{t('card.noDescription')}</p>;
  }
  const { rec } = pick;
  return (
    <>
      <p className="badge-checked"><Icon name="check" />{t('guide.quoteChecked')}</p>
      <ProofQuote text={rec.description ?? card.description ?? rec.quote} quote={rec.quote} source={listedBy(card)} />
      {(rec.checks?.length ?? 0) > 0 && (
        <ul className="plain-list guide-checks" aria-label={t('guide.checksLabel')}>
          {rec.checks!.map((c) => (
            <li key={c.constraint} className={c.status === 'CONFIRMED' ? 'check-ok' : 'check-unknown'}>
              {/* Status in words, never colour alone (WCAG 1.4.1). */}
              <Icon name={c.status === 'CONFIRMED' ? 'check' : 'info'} />{' '}
              <strong {...aiMark()}>{c.constraint}</strong>:{' '}
              {c.status === 'CONFIRMED' ? <>{t('guide.checkConfirmed')} <q>{c.quote}</q></> : t('guide.checkNotConfirmed')}
            </li>
          ))}
        </ul>
      )}
      {rec.reasonCheck === 'REMOVED' && <p className="guide-reason muted small"><Icon name="info" /> {t('guide.reasonRemoved')}</p>}
      {rec.reasonCheck !== 'REMOVED' && rec.reason && (
        <details className="guide-reason" open>
          <summary>{t(rec.reasonCheck === 'SUPPORTED' ? 'guide.reasonSupported' : 'guide.reasonLabel')}</summary>
          <p {...aiMark()}>{rec.reason}</p>
        </details>
      )}
    </>
  );
}

/**
 * The selected result, next to the list from `bp-split` up (ADR 0040, 4): type and site, the title, the actions
 * (open on the source, save, Guide me, full details), then why it matches, its facts, the sources that list it and
 * its dates. Everything comes from the search answer: no request of its own. [statuses] mark sources served from a
 * stale cache.
 */
export function ResultPreview({ card, pick, statuses }: { card: ResultCard; pick: GuidePick | undefined; statuses: SourceStatus[] }) {
  const { t } = useTranslation();
  const titleId = useId();
  const guide = useGuideToggle();
  const guideBodyId = `guide-${useId()}`;
  const guideButton = useRef<HTMLButtonElement>(null);
  const toggleGuide = () => {
    // Closing returns focus to the button; opening moves it to the panel heading (GuideBody).
    if (guide.open) guideButton.current?.focus();
    guide.toggle();
  };
  const stale = new Set(statuses.filter((s) => s.servedFrom === 'STALE_CACHE').map((s) => s.sourceId));

  return (
    <section className="result-preview" aria-labelledby={titleId}>
      <p className="result-preview__meta">
        <TypeTag type={card.type} /> <span>{hostname(card.url)}</span> <FreshnessBadge freshness={card.freshness} />
      </p>
      <h2 id={titleId} className="result-preview__title">{card.title}</h2>
      <div className="result-preview__actions">
        <ExternalLink url={card.url} showHost={false}>{t('preview.open')}</ExternalLink>
        {SAVED_ENABLED && <BookmarkButton card={card} />}
        {canGuide(card) && <GuideMeButton title={card.title} open={guide.open} onToggle={toggleGuide} bodyId={guideBodyId} buttonRef={guideButton} />}
        <Link to={`/resource/${card.id}`}>{t('preview.details')} →</Link>
      </div>
      {canGuide(card) && (
        <div id={guideBodyId} hidden={!guide.open}>
          {guide.open && <GuideBody id={card.id} title={card.title} level={3} />}
        </div>
      )}

      <h3 className="preview-label">{t(pick ? 'preview.why' : 'preview.description')}</h3>
      <WhyItMatches card={card} pick={pick} />

      <h3 className="preview-label">{t('preview.facts')}</h3>
      {card.conflicts.length > 0 && (
        <ul className="plain-list conflicts" aria-label={t('card.conflicts')}>
          {card.conflicts.map((c) => (
            <li key={c.field}>
              <Icon name="warn" /><strong>{t('card.conflictOn', { field: c.field })}</strong>{' '}
              <span className="muted">{c.values.map((v) => `${v.value} (${v.sourceId})`).join(' · ')}</span>
            </li>
          ))}
        </ul>
      )}
      <FactList card={card} all />
      <WorksWith card={card} />
      <Popularity card={card} />

      <h3 className="preview-label">{t('preview.listedBy')}</h3>
      <ul className="plain-list preview-sources">
        {card.sources.map((s) => (
          <li key={s.sourceId}>
            <ExternalLink url={s.url} showHost={false}>{s.sourceName}</ExternalLink> <ModeBadge mode={s.mode} />
            {stale.has(s.sourceId) && <span className="badge served served-stale_cache"><Icon name="warn" />{t('servedFrom.STALE_CACHE')}</span>}
          </li>
        ))}
      </ul>

      <h3 className="preview-label">{t('preview.timeline')}</h3>
      <Timeline timeline={card.timeline} />
    </section>
  );
}

/**
 * The selected result under its row, up to `bp-split` (ADR 0040, 4): why it matches, three stated facts, and links
 * to the source and to the full resource page.
 */
export function InlinePreview({ card, pick }: { card: ResultCard; pick: GuidePick | undefined }) {
  const { t } = useTranslation();
  return (
    <div className="inline-preview" role="group" aria-label={t('preview.label', { title: card.title })}>
      <WhyItMatches card={card} pick={pick} />
      <FactList card={card} limit={3} />
      <div className="result-preview__actions">
        <ExternalLink url={card.url} showHost={false}>{t('preview.open')}</ExternalLink>
        <Link to={`/resource/${card.id}`}>{t('preview.details')} →</Link>
      </div>
    </div>
  );
}
