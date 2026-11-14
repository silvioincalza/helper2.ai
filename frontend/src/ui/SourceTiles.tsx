import { useTranslation } from 'react-i18next';
import type { SourceStatus } from '@/api/types';
import { relativeTime } from '@/lib/format';
import './ui.css';

type TileState = 'ok' | 'warn' | 'failed' | 'off';

function tileState(s: SourceStatus): TileState {
  if (s.outcome === 'SKIPPED') return 'off';
  if (s.outcome === 'FAILED' || s.outcome === 'TIMED_OUT') return 'failed';
  if (s.outcome === 'RATE_LIMITED' || s.servedFrom === 'CACHE' || s.servedFrom === 'STALE_CACHE')
    return 'warn';
  return 'ok';
}

function age(iso: string, now: number): string {
  const minutes = Math.max(0, Math.round((now - Date.parse(iso)) / 60_000));
  const rtf = relativeTime('short');
  if (minutes < 60) return rtf.format(-minutes, 'minute');
  if (minutes < 24 * 60) return rtf.format(-Math.round(minutes / 60), 'hour');
  return rtf.format(-Math.round(minutes / (24 * 60)), 'day');
}

export function SourceTiles({ sources, now }: { sources: SourceStatus[]; now: number }) {
  const { t } = useTranslation();
  const detail = (s: SourceStatus): string => {
    if (s.outcome !== 'SUCCEEDED') return t(`outcome.${s.outcome}`);
    if (s.servedFrom !== 'LIVE' && s.cachedAt)
      return t('sourceTile.cached', { age: age(s.cachedAt, now) });
    return s.latencyMs == null
      ? t('outcome.SUCCEEDED')
      : t('sources.latencyMs', { ms: s.latencyMs });
  };
  return (
    <ul className="h2-source-tiles" aria-label={t('sourceTile.label')}>
      {sources.map((s) => (
        <li key={s.sourceId} className={`h2-source-tile is-${tileState(s)}`}>
          <span className="h2-source-tile__dot" aria-hidden="true" />
          <span className="h2-source-tile__name">{s.name}</span>
          <span className="h2-source-tile__detail">{detail(s)}</span>
        </li>
      ))}
    </ul>
  );
}
