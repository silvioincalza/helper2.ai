import { useTranslation } from 'react-i18next';
import type { ResultCard } from '@/api/types';
import { SAVED_ENABLED } from '@/lib/features';
import { BookmarkButton } from './BookmarkButton';
import { FactList, WorksWith } from './CardFacts';
import { FreshnessBadge, ModeBadge, OriginLabel } from './Badges';
import { ExternalLink } from './ExternalLink';
import { Icon } from './Icon';
import { Popularity } from './Popularity';
import { Term, type GlossaryKey } from './Term';
import { Timeline } from './Timeline';
import { typeTagClass } from '@/lib/typeTag';
import { aiMark, isAiOrigin } from '@/lib/aiMark';

/** Every type has a definition, so every card can say where its type comes from. */
const TYPE_TERM: Record<ResultCard['type'], GlossaryKey> = {
  MCP_SERVER: 'mcp_server', MODEL: 'model', AGENT: 'agent', PROMPT: 'prompt', SKILL: 'skill', LIBRARY: 'library', DATASET: 'dataset',
  PRODUCT: 'product', ARTICLE: 'article', PAPER: 'paper', NEWS: 'news', EVENT: 'event', COURSE: 'course', OTHER: 'other',
};

/** The resource page's card: every fact (Unknown included), every date, the sources and their modes. */
export function ResultCardView({ card }: { card: ResultCard }) {
  const { t } = useTranslation();
  const titleId = `card-${card.id}`;
  const typeTerm = TYPE_TERM[card.type] as GlossaryKey | undefined;

  return (
    <article className="card" aria-labelledby={titleId}>
      <header className="card-head">
        <div className="card-badges">
          <span className={`type ${typeTagClass(card.type)}`}>{typeTerm ? <Term term={typeTerm} note={t('glossary.typeAssigned')}>{t(`type.${card.type}`)}</Term> : t(`type.${card.type}`)}</span>
          <FreshnessBadge freshness={card.freshness} />
        </div>
        <h1 id={titleId} className="card-title">{card.title}</h1>
        <div className="card-actions">
          <ExternalLink url={card.url} />
          {SAVED_ENABLED && <BookmarkButton card={card} />}
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

      <div className="card-desc">
        {card.description
          ? <p {...(isAiOrigin(card.descriptionOrigin) ? aiMark() : {})}>{card.description}</p>
          : <p className="muted">{t('card.noDescription')}</p>}
        <p className="muted small">
          {t('card.descriptionOrigin')}: <OriginLabel origin={card.descriptionOrigin} />
        </p>
      </div>
      <WorksWith card={card} />
      <FactList card={card} all />
      <Popularity card={card} />
      {card.tags.length > 0 && (
        <ul className="tags plain-list" aria-label={t('card.tags')}>
          {card.tags.map((tag) => <li key={tag} className="tag">{tag}</li>)}
        </ul>
      )}

      <div className="card-sources">
        <span className="muted small">{t('card.sources')}:</span>{' '}
        <ul className="plain-list inline-list">
          {card.sources.map((s) => (
            <li key={s.sourceId} className="source-chip">{s.sourceName} <ModeBadge mode={s.mode} /></li>
          ))}
        </ul>
      </div>

      <Timeline timeline={card.timeline} />
    </article>
  );
}
