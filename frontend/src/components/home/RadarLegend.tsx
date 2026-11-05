import { useTranslation } from 'react-i18next';
import type { SourceInfo } from '@/api/types';
import { newThisWeek } from '@/lib/v4Fields';

/** What the dots mean. "bigger = more new this week" only when the API sends that number. */
export function RadarLegend({ sources }: { sources: SourceInfo[] }) {
  const { t } = useTranslation();
  const sized = sources.some((s) => newThisWeek(s) !== null);
  return (
    <p className="v4-legend" aria-hidden="true">
      {t('radar.legend.each')} <span className="v4-legend__key v4-dot--live">●</span>{' '}
      {t('radar.legend.live')} <span className="v4-legend__key v4-dot--index">○</span>{' '}
      {t('radar.legend.index')} <span className="v4-legend__key v4-dot--warn">!</span>{' '}
      {t('radar.legend.warn')} <span className="v4-legend__key v4-dot--failed">×</span>{' '}
      {t('radar.legend.failed')}
      <br />
      {sized && <>{t('radar.legend.size')} · </>}
      {t('radar.legend.distance')}
    </p>
  );
}
