import { useTranslation } from 'react-i18next';
import type { SearchResponse } from '@/api/types';

/**
 * One line under the box, in the terminal style (v5): how many sources answered, how many answered live and the
 * slowest of those, how many came from the cache. All numbers come from the search response. "Details" jumps to the
 * source tiles under the list. Nothing when some sources did not answer: the heading's coverage line says that, so
 * the page says one thing. Not a live region: the result count already says that the list changed.
 */
export function SourceLine({ res }: { res: SearchResponse }) {
  const { t } = useTranslation();
  if (res.coverage.partial || res.coverage.sourcesSucceeded === 0) return null;
  const answered = res.sources.filter((s) => s.outcome === 'SUCCEEDED');
  const live = answered.filter((s) => s.servedFrom === 'LIVE');
  const cached = answered.length - live.length;
  const slowest = Math.max(0, ...live.map((s) => s.latencyMs ?? 0));
  return (
    <p className="h2-source-line">
      <span className="h2-source-line__dot" aria-hidden="true" />
      {t('results.sourceLine.answered', {
        ok: res.coverage.sourcesSucceeded,
        total: res.coverage.sourcesQueried,
      })}
      {live.length > 0 && (
        <>
          <span aria-hidden="true"> · </span>
          {slowest > 0
            ? t('results.sourceLine.live', { count: live.length, ms: slowest })
            : t('results.sourceLine.liveOnly', { count: live.length })}
        </>
      )}
      {cached > 0 && (
        <>
          <span aria-hidden="true"> · </span>
          {t('results.sourceLine.cached', { count: cached })}
        </>
      )}
      <span aria-hidden="true"> · </span>
      <a href="#h2-sources">{t('results.sourceLine.details')}</a>
    </p>
  );
}
