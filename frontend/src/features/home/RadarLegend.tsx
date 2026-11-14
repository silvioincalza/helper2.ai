import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { SourceInfo } from '@/api/types';
import { bounds } from './radarRotation';

export function RadarLegend({ sources }: { sources: SourceInfo[] }) {
  const { t } = useTranslation();
  const { fewest, most } = bounds(sources.length);
  const swatch = (tone: string) => (
    <span className={`h2-legend__key h2-dot--${tone}`} aria-hidden="true">
      ●
    </span>
  );
  return (
    <p className="h2-legend">
      {t('radar.legend.atATime', {
        shown: fewest === most ? `${most}` : `${fewest}-${most}`,
        count: sources.length,
      })}{' '}
      · {swatch('answering')} {t('radar.legend.answering')} {swatch('warn')}{' '}
      {t('radar.legend.warn')} {swatch('failed')} {t('radar.legend.failed')} ·{' '}
      <Link to="/sources" className="h2-legend__link">
        {t('radar.legend.all')}
      </Link>
    </p>
  );
}
