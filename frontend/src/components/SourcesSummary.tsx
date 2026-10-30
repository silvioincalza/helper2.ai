import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import type { SearchResponse, SourceOutcome } from '@/api/types';
import { SourceTiles } from '@/components/ui/SourceTiles';

type WebSearches = NonNullable<SearchResponse['webSearches']>;

/** How many sources answered, then each other outcome and the stale caches, in words. */
function outcomes(res: SearchResponse, t: TFunction): string {
  const parts = [t('sources.summary', { ok: res.coverage.sourcesSucceeded, total: res.coverage.sourcesQueried })];
  const counts = new Map<SourceOutcome, number>();
  for (const s of res.sources) if (s.outcome !== 'SUCCEEDED') counts.set(s.outcome, (counts.get(s.outcome) ?? 0) + 1);
  for (const [o, n] of counts) parts.push(t(`sources.count.${o}`, { count: n }));
  const stale = res.sources.filter((s) => s.servedFrom === 'STALE_CACHE').length;
  if (stale) parts.push(t('sources.count.STALE', { count: stale }));
  return parts.join(' · ');
}

/**
 * The live web searches the visitor has left today, per feature (ADR 0037), in one quiet line; at the daily limit it
 * says that stored web results still answer and when new ones come back. Nothing when no web source is metered.
 */
function WebSearchesLine({ search, guide }: { search: WebSearches | null | undefined; guide: WebSearches | null | undefined }) {
  const { t } = useTranslation();
  const parts = (['search', 'guide'] as const).flatMap((feature) => {
    const left = feature === 'search' ? search : guide;
    if (!left) return [];
    const name = t(`webSearches.${feature}`);
    return [left.remaining > 0
      ? t('webSearches.left', { remaining: left.remaining, limit: left.limit, feature: name })
      : t('webSearches.limitReached', { feature: name })];
  });
  if (parts.length === 0) return null;
  return <p className="sources-summary__web">{t('webSearches.label')}: {parts.join(' · ')}</p>;
}

/**
 * Under the results (ADR 0040, 7): the fan-out in one mono line (sources asked, their hits, the results left after
 * merging), how they answered, a tile per source with its time or cache age, and the web searches left today.
 * [guideWebSearches] comes from the guide's answer, when the page asked it.
 */
export function SourcesSummary({ res, guideWebSearches }: { res: SearchResponse; guideWebSearches?: WebSearches | null | undefined }) {
  const { t } = useTranslation();
  const hits = res.sources.reduce((n, s) => n + s.resultCount, 0);
  return (
    <section className="sources-summary" aria-labelledby="sources-h">
      <h2 id="sources-h" tabIndex={-1} className="preview-label">{t('sources.title')}</h2>
      <p className="sources-summary__fanout">{t('sources.fanout', { sources: res.coverage.sourcesQueried, hits, merged: res.total })}</p>
      <p className="small">{outcomes(res, t)}</p>
      <SourceTiles sources={res.sources} now={Date.parse(res.executedAt)} />
      <WebSearchesLine search={res.webSearches} guide={guideWebSearches} />
      <p className="muted small">{res.coverage.note}</p>
    </section>
  );
}
