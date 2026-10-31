import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import type { SourceInfo, SourceOutcome, SourceStatus } from '@/api/types';
import './ui.css';

type TileState = 'ok' | 'warn' | 'failed' | 'off';

/** One tile: the source's name, its state (the dot's colour) and that state in words. */
interface Tile {
  id: string;
  name: string;
  state: TileState;
  detail: string;
}

/** How long ago [iso] was, in the largest whole unit (minutes, hours, days), worded by the browser for [lang]. */
function age(iso: string, now: number, lang: string): string {
  const minutes = Math.max(0, Math.round((now - Date.parse(iso)) / 60_000));
  const rtf = new Intl.RelativeTimeFormat(lang, { numeric: 'auto', style: 'short' });
  if (minutes < 60) return rtf.format(-minutes, 'minute');
  if (minutes < 24 * 60) return rtf.format(-Math.round(minutes / 60), 'hour');
  return rtf.format(-Math.round(minutes / (24 * 60)), 'day');
}

function outcomeState(outcome: SourceOutcome): TileState {
  if (outcome === 'SKIPPED') return 'off';
  if (outcome === 'FAILED' || outcome === 'TIMED_OUT') return 'failed';
  if (outcome === 'RATE_LIMITED') return 'warn';
  return 'ok';
}

/** A source in one search: its response time, its cache age or what went wrong. */
function searchTile(s: SourceStatus, now: number, t: TFunction, lang: string): Tile {
  const cached = s.servedFrom === 'CACHE' || s.servedFrom === 'STALE_CACHE';
  const state = s.outcome === 'SUCCEEDED' && cached ? 'warn' : outcomeState(s.outcome);
  let detail: string;
  if (s.outcome !== 'SUCCEEDED') detail = t(`outcome.${s.outcome}`);
  else if (cached && s.cachedAt) detail = t('sourceTile.cached', { age: age(s.cachedAt, now, lang) });
  else detail = s.latencyMs == null ? t('outcome.SUCCEEDED') : t('sources.latencyMs', { ms: s.latencyMs });
  return { id: s.sourceId, name: s.name, state, detail };
}

/** A source of the registry: turned off, not asked yet, or its last outcome and how long ago that was. */
function registryTile(s: SourceInfo, now: number, t: TFunction, lang: string): Tile {
  if (!s.enabled) return { id: s.id, name: s.name, state: 'off', detail: t('sourceTile.off') };
  if (!s.lastOutcome) return { id: s.id, name: s.name, state: 'off', detail: t('sourceTile.notChecked') };
  const outcome = t(`outcome.${s.lastOutcome}`);
  const detail = s.lastCheckedAt ? t('sourceTile.checked', { outcome, age: age(s.lastCheckedAt, now, lang) }) : outcome;
  return { id: s.id, name: s.name, state: outcomeState(s.lastOutcome), detail };
}

/**
 * One tile per source: a status dot, the name, and its state in words; the dot only repeats the words. With
 * [search], the sources of one search (response time, cache age or what went wrong); with [registry], every
 * configured source (its last outcome and when, not asked yet, or turned off). [now] is when the ages are read.
 */
export function SourceTiles(props: { now: number; label?: string } & ({ search: SourceStatus[] } | { registry: SourceInfo[] })) {
  const { t, i18n } = useTranslation();
  const tiles = 'search' in props
    ? props.search.map((s) => searchTile(s, props.now, t, i18n.language))
    : props.registry.map((s) => registryTile(s, props.now, t, i18n.language));
  return (
    <ul className="h2-source-tiles" aria-label={props.label ?? t('sourceTile.label')}>
      {tiles.map((s) => (
        <li key={s.id} className={`h2-source-tile is-${s.state}`}>
          <span className="h2-source-tile__dot" aria-hidden="true" />
          <span className="h2-source-tile__name">{s.name}</span>
          <span className="h2-source-tile__detail">{s.detail}</span>
        </li>
      ))}
    </ul>
  );
}
