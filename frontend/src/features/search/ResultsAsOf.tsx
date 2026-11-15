import { useTranslation } from 'react-i18next';
import type { SourceStatus } from '@/api/types';
import { clockTime } from '@/lib/format';

function oldestCachedAt(sources: readonly SourceStatus[]): string | null {
  const saved = sources
    .filter((s) => s.servedFrom === 'CACHE' || s.servedFrom === 'STALE_CACHE')
    .flatMap((s) => (s.cachedAt && !Number.isNaN(Date.parse(s.cachedAt)) ? [s.cachedAt] : []));
  if (saved.length === 0) return null;
  return saved.reduce((oldest, at) => (Date.parse(at) < Date.parse(oldest) ? at : oldest));
}

export function ResultsAsOf({ sources }: { sources: readonly SourceStatus[] }) {
  const { t } = useTranslation();
  const at = oldestCachedAt(sources);
  if (!at) return null;
  return (
    <>
      <span aria-hidden="true"> · </span>
      <time dateTime={at}>{t('results.asOf', { time: clockTime(at, false) })}</time>
    </>
  );
}
