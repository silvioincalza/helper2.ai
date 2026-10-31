import { useId, useRef, type ReactNode, type Ref } from 'react';
import { Link, useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResultCard, SourceStatus } from '@/api/types';
import { Heading } from '@/components/ui/Heading';
import { ProofQuote } from '@/components/ui/ProofQuote';
import { TypeTag } from '@/components/ui/TypeTag';
import { aiMark, isAiOrigin } from '@/lib/aiMark';
import { hostname } from '@/lib/format';
import { FreshnessBadge, ModeBadge, OriginLabel } from './Badges';
import { BookmarkButton } from './BookmarkButton';
import { FactList, WorksWith } from './CardFacts';
import { ExternalLink } from './ExternalLink';
import type { GuidePick } from './GuidePicks';
import { Icon } from './Icon';
import { Popularity } from './Popularity';
import { GuideBody } from './setup/GuideBody';
import { canGuide, GuideMeButton, useGuideToggle } from './setup/GuideMe';
import { Term, type GlossaryKey } from './Term';
import { Timeline } from './Timeline';

/** Every type has a definition, so the resource page can say what its type means. */
const TYPE_TERM: Record<ResultCard['type'], GlossaryKey> = {
  MCP_SERVER: 'mcp_server', MODEL: 'model', AGENT: 'agent', PROMPT: 'prompt', SKILL: 'skill', LIBRARY: 'library', DATASET: 'dataset',
  PRODUCT: 'product', ARTICLE: 'article', PAPER: 'paper', NEWS: 'news', EVENT: 'event', COURSE: 'course', OTHER: 'other',
};

/** The sources that listed [card], in words, for the proof quote's caption. */
const listedBy = (card: ResultCard) => card.sources.map((s) => s.sourceName).join(', ');

/** Where the resource page's Back to results returns: the results page the link was followed from. */
export interface FromResults {
  from: string;
}

/** "Full details": the resource page, which can step back to these results. */
export function DetailsLink({ card }: { card: ResultCard }) {
  const { t } = useTranslation();
  const { pathname, search } = useLocation();
  const state: FromResults = { from: `${pathname}${search}` };
  return <Link to={`/resource/${card.id}`} state={state}>{t('preview.details')} →</Link>;
}

/**
 * One part of the overview or the resource page: a mono label at [level], then its content. Not a named region, so
 * the parts do not crowd the landmarks; the headings are the way through.
 */
export function Part({ label, level, children }: { label: string; level: number; children: ReactNode }) {
  return (
    <section className="resource-part">
      <Heading level={level} className="preview-label">{label}</Heading>
      {children}
    </section>
  );
}

/**
 * Why the result matches: for a guide pick, the source's text with the sentence the guide checked highlighted, the
 * user's requirements checked against it, and the model's explanation labelled with the outcome of its check; for
 * any other result, its description and where that came from.
 */
export function WhyItMatches({ card, pick }: { card: ResultCard; pick: GuidePick | undefined }) {
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
  const { rec, model } = pick;
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
          <p {...aiMark(model)}>{rec.reason}</p>
        </details>
      )}
    </>
  );
}

/**
 * What the results preview and the resource page show alike (ADR 0040, 4): type and site, the title, the actions
 * (open on the source, save, Guide me, and in the preview Full details), Guide me's panel, then why it matches or its
 * description, its facts, the sources that list it and its dates. Everything comes from the card: no request of its
 * own. The preview's title is an h2; on the resource page ([page]) it is the h1, and the page adds what the type
 * means and the tags. Part labels sit one level under the title. [statuses] mark sources served from a stale cache.
 */
export function ResourceOverview({ card, pick, statuses = [], page = false, titleId, titleRef }: {
  card: ResultCard;
  pick?: GuidePick | undefined;
  statuses?: SourceStatus[];
  page?: boolean;
  titleId: string;
  titleRef?: Ref<HTMLHeadingElement>;
}) {
  const { t } = useTranslation();
  const level = page ? 1 : 2;
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
    <>
      <p className="result-preview__meta">
        <TypeTag type={card.type} /> <span>{hostname(card.url)}</span> <FreshnessBadge freshness={card.freshness} />
      </p>
      <Heading level={level} id={titleId} ref={titleRef} tabIndex={page ? -1 : undefined} className="resource-title">{card.title}</Heading>
      <div className="result-preview__actions">
        <ExternalLink url={card.url} showHost={false}>{t('preview.open')}</ExternalLink>
        <BookmarkButton card={card} />
        {canGuide(card) && <GuideMeButton title={card.title} open={guide.open} onToggle={toggleGuide} bodyId={guideBodyId} buttonRef={guideButton} />}
        {!page && <DetailsLink card={card} />}
      </div>
      {canGuide(card) && (
        <div id={guideBodyId} hidden={!guide.open}>
          {guide.open && <GuideBody id={card.id} title={card.title} level={level + 1} />}
        </div>
      )}

      <Part label={t(pick ? 'preview.why' : 'preview.description')} level={level + 1}>
        <WhyItMatches card={card} pick={pick} />
        {page && (
          <p className="muted small">
            {t('facet.type')}: <Term term={TYPE_TERM[card.type]} note={t('glossary.typeAssigned')}>{t(`type.${card.type}`)}</Term>
          </p>
        )}
      </Part>

      <Part label={t('preview.facts')} level={level + 1}>
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
        {page && card.tags.length > 0 && (
          <ul className="plain-list tags" aria-label={t('card.tags')}>
            {card.tags.map((tag) => <li key={tag} className="tag">{tag}</li>)}
          </ul>
        )}
      </Part>

      <Part label={t('preview.listedBy')} level={level + 1}>
        <ul className="plain-list preview-sources">
          {card.sources.map((s) => (
            <li key={s.sourceId}>
              <ExternalLink url={s.url} showHost={false}>{s.sourceName}</ExternalLink> <ModeBadge mode={s.mode} />
              {stale.has(s.sourceId) && <span className="badge served served-stale_cache"><Icon name="warn" />{t('servedFrom.STALE_CACHE')}</span>}
            </li>
          ))}
        </ul>
      </Part>

      <Part label={t('preview.timeline')} level={level + 1}>
        <Timeline timeline={card.timeline} />
      </Part>
    </>
  );
}
