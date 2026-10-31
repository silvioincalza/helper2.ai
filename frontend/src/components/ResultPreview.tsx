import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import type { ResultCard, SourceStatus } from '@/api/types';
import { FactList } from './CardFacts';
import { ExternalLink } from './ExternalLink';
import type { GuidePick } from './GuidePicks';
import { DetailsLink, ResourceOverview, WhyItMatches } from './ResourceOverview';

/**
 * The selected result, next to the list from `bp-split` up (ADR 0040, 4): the resource overview the resource page
 * shows too, with Full details. [statuses] mark sources served from a stale cache.
 */
export function ResultPreview({ card, pick, statuses }: { card: ResultCard; pick: GuidePick | undefined; statuses: SourceStatus[] }) {
  const titleId = useId();
  return (
    <section className="result-preview" aria-labelledby={titleId}>
      <ResourceOverview card={card} pick={pick} statuses={statuses} titleId={titleId} />
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
        <DetailsLink card={card} />
      </div>
    </div>
  );
}
