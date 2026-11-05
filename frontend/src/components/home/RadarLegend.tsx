import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { SourceInfo } from '@/api/types';
import { bounds } from './radarRotation';

/**
 * Under the box: how many sources the radar shows of how many ("3-5 of 13 sources at a time", 13 from GET /sources),
 * a filled swatch for each colour (answering, slow or limited, failed), and a link to the Sources page, which lists
 * them all. Each dot also says its state in its accessible name and tooltip.
 */
export function RadarLegend({ sources }: { sources: SourceInfo[] }) {
  const { t } = useTranslation();
  const { fewest, most } = bounds(sources.length);
  const swatch = (tone: string) => (
    <span className={`v4-legend__key v4-dot--${tone}`} aria-hidden="true">
      ●
    </span>
  );
  return (
    <p className="v4-legend">
      {t('radar.legend.atATime', {
        shown: fewest === most ? `${most}` : `${fewest}-${most}`,
        count: sources.length,
      })}{' '}
      · {swatch('answering')} {t('radar.legend.answering')} {swatch('warn')}{' '}
      {t('radar.legend.warn')} {swatch('failed')} {t('radar.legend.failed')} ·{' '}
      <Link to="/sources" className="v4-legend__link">
        {t('radar.legend.all')}
      </Link>
    </p>
  );
}
