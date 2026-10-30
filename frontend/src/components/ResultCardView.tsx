import { useId, useRef } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResultCard, SourceStatus } from '@/api/types';
import { SAVED_ENABLED } from '@/lib/features';
import { BookmarkButton } from './BookmarkButton';
import { canGuide } from './GuideMe';
import { GuideBody, GuideMeButton, useGuideToggle } from './SetupGuide';
import { FreshnessBadge, ModeBadge, OriginLabel } from './Badges';
import { ExternalLink } from './ExternalLink';
import { Icon } from './Icon';
import { Popularity } from './Popularity';
import { Term, type GlossaryKey } from './Term';
import { Timeline } from './Timeline';
import { typeTagClass } from '@/lib/typeTag';

/** Every type has a definition, so every card can say where its type comes from. */

const TYPE_TERM: Record<ResultCard['type'], GlossaryKey> = {
  MCP_SERVER: 'mcp_server', MODEL: 'model', AGENT: 'agent', PROMPT: 'prompt', SKILL: 'skill', LIBRARY: 'library', DATASET: 'dataset',
  PRODUCT: 'product', ARTICLE: 'article', PAPER: 'paper', NEWS: 'news', EVENT: 'event', COURSE: 'course', OTHER: 'other',
};

interface Fact {
  key: string;
  label: React.ReactNode;
  /** null = no source stated it. */
  value: string | null;
}

interface Props {
  card: ResultCard;
  sourceStatuses?: SourceStatus[];
  /** Detail page: show every fact, Unknown included. */
  detailed?: boolean;
}

export function ResultCardView({ card, sourceStatuses = [], detailed = false }: Props) {
  const { t } = useTranslation();
  const Heading = detailed ? 'h1' : 'h3';
  const titleId = `card-${card.id}`;
  const staleIds = new Set(sourceStatuses.filter((s) => s.servedFrom === 'STALE_CACHE').map((s) => s.sourceId));

  // Each fact is null when no source stated it. The detail page shows every fact (Unknown included);
  // a result card shows only known facts plus a count, so a list of results stays readable.
  const useFacts: Fact[] = [
    { key: 'pricing', label: t('card.pricing'), value: card.pricing ? t(`pricing.${card.pricing}`) : null },
    { key: 'platforms', label: t('card.platforms'), value: card.platforms.length ? card.platforms.join(', ') : null },
    {
      key: 'useCases', label: t('card.useCases'),
      value: card.useCases.length ? card.useCases.map((u) => t(`useCase.${u}`, { defaultValue: u })).join(', ') : null,
    },
  ];
  const buildFacts: Fact[] = [
    { key: 'license', label: <Term term="license">{t('card.license')}</Term>, value: card.license ?? null },
    {
      key: 'openSource', label: <Term term="open_source">{t('card.openSource')}</Term>,
      value: card.openSource === true ? t('common.yes') : card.openSource === false ? t('common.no') : null,
    },
    { key: 'techLevel', label: <Term term="tech_level">{t('card.techLevel')}</Term>, value: card.techLevel ? t(`techLevel.${card.techLevel}`) : null },
  ];
  const unknownCount = [...useFacts, ...buildFacts].filter((f) => f.value === null).length;

  const description = (
    <div className="card-desc" key="desc">
      {card.description ? <p>{card.description}</p> : <p className="muted">{t('card.noDescription')}</p>}
      {(detailed || card.descriptionOrigin !== 'SOURCE_PROVIDED') && (
        <p className="muted small">
          {t('card.descriptionOrigin')}: <OriginLabel origin={card.descriptionOrigin} />
        </p>
      )}
    </div>
  );

  const factList = (block: 'use' | 'build', facts: Fact[], extra: Fact[]) => {
    const shown = detailed ? [...facts, ...extra] : facts.filter((f) => f.value !== null);
    if (shown.length === 0) return null;
    return (
      <dl className={`facts${detailed ? '' : ' facts-compact'}`} data-block={block} key={block}>
        {shown.map((f) => <div key={f.key}><dt>{f.label}</dt><dd>{f.value ?? t('common.unknown')}</dd></div>)}
      </dl>
    );
  };
  const useMeta = factList('use', useFacts, [{ key: 'audience', label: t('card.audience'), value: t(`audience.${card.audience}`) }]);
  const buildMeta = factList('build', buildFacts, [{ key: 'records', label: t('card.sourceRecords'), value: String(card.sources.length) }]);
  const unknownNote = !detailed && unknownCount > 0 && (
    <p className="muted small card-unknown" key="unknown">
      {t('card.unknownFacts', { count: unknownCount })} · <Link to={`/resource/${card.id}`}>{t('card.allFacts')}</Link>
    </p>
  );

  const tags = card.tags.length > 0 && (
    <ul className="tags plain-list" aria-label={t('card.tags')} key="tags">
      {card.tags.map((tag) => <li key={tag} className="tag">{tag}</li>)}
    </ul>
  );

  // Derived by disclosed rules from what sources said; the tooltip names the tag/type and source.
  const compat = card.compatibleWith.length > 0 && (
    <div className="compat" key="compat">
      <span className="compat-label small">{t('card.compatibleWith')}</span>
      <ul className="compat-list plain-list" aria-label={t('card.compatibleWith')}>
        {card.compatibleWith.map((f) => {
          const why = t(`compat.basis.${f.basis}`, { evidence: f.evidence, source: f.sourceId ?? '' });
          const label = <>{f.label}<span className="sr-only"> ({why})</span></>;
          return (
            <li key={f.tool} data-tool={f.tool}>
              <span className="compat-chip" title={why}>{label}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );

  const popularity = <Popularity card={card} key="pop" />;

  const parts = detailed
    ? [description, compat, useMeta, buildMeta, popularity, tags]
    : [description, compat, useMeta, buildMeta, popularity, tags, unknownNote];

  const typeTerm = TYPE_TERM[card.type] as GlossaryKey | undefined;
  // "Guide me" in a list: the same panel as on the detail page, opened under the card (the detail page has its own).
  const guidable = !detailed && canGuide(card);
  const guide = useGuideToggle(card.id);
  const guideBodyId = `guide-${useId()}`;
  const guideButton = useRef<HTMLButtonElement>(null);
  const toggleGuide = () => {
    // Closing returns focus to the button; opening moves it to the panel heading (GuideBody).
    if (guide.open) guideButton.current?.focus();
    guide.toggle();
  };

  return (
    <article className="card" aria-labelledby={titleId}>
      <header className="card-head">
        <div className="card-badges">
          <span className={`type ${typeTagClass(card.type)}`}>{typeTerm ? <Term term={typeTerm} note={t('glossary.typeAssigned')}>{t(`type.${card.type}`)}</Term> : t(`type.${card.type}`)}</span>
          <FreshnessBadge freshness={card.freshness} />
        </div>
        <Heading id={titleId} className="card-title">
          {detailed ? card.title : <Link to={`/resource/${card.id}`}>{card.title}</Link>}
        </Heading>
        <div className="card-actions">
          <ExternalLink url={card.url} />
          {SAVED_ENABLED && <BookmarkButton card={card} />}
          {guidable && <GuideMeButton title={card.title} open={guide.open} onToggle={toggleGuide} bodyId={guideBodyId} buttonRef={guideButton} />}
        </div>
      </header>

      {card.conflicts.length > 0 && (
        <ul className="plain-list conflicts" aria-label={t('card.conflicts')}>
          {card.conflicts.map((c) => (
            <li key={c.field}>
              <Icon name="warn" />
              <strong>{t('card.conflictOn', { field: c.field })}</strong>{' '}
              <span className="muted">
                {c.values.map((v) => `${v.value} (${v.sourceId})`).join(' · ')}
              </span>
            </li>
          ))}
        </ul>
      )}

      {parts}

      <div className="card-sources">
        <span className="muted small">{t('card.sources')}:</span>{' '}
        <ul className="plain-list inline-list">
          {card.sources.map((s) => (
            <li key={s.sourceId} className="source-chip">
              {s.sourceName} <ModeBadge mode={s.mode} />
              {staleIds.has(s.sourceId) && (
                <span className="badge served served-stale_cache"><Icon name="warn" />{t('servedFrom.STALE_CACHE')}</span>
              )}
            </li>
          ))}
        </ul>
      </div>

      <Timeline timeline={card.timeline} compact={!detailed} />

      {guidable && (
        <div id={guideBodyId} className="card-guide" hidden={!guide.open}>
          {guide.open && <GuideBody id={card.id} title={card.title} level={4} />}
        </div>
      )}
    </article>
  );
}
