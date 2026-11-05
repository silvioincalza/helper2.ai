import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { SourceInfo } from '@/api/types';
import { responseTimeLabel } from '@/lib/sourceHealth';
import { newThisWeek } from '@/lib/v4Fields';

/** The drawing's own units: the radar's centre sits in the middle of the hero, the dots left and right of the text. */
const W = 1280;
const H = 400;
const CX = W / 2;
const CY = H / 2;
/** How far above and below the centre the dots sit, so they and their labels stay inside the hero. */
const RISE = 160;
const FALL = 130;
/** Live sources in the inner band, index and curated ones in the outer band (radius in drawing units). */
const BANDS = { inner: [370, 470], outer: [490, 600] } as const;
const RINGS = [130, 250, 370, 490, 610];
/** A live answer this slow, or an index check this old, sits at the outer edge of its band. */
const SLOW_MS = 2000;
const OLD_MS = 24 * 3_600_000;
const DOT = 6;

export type DotTone = 'live' | 'index' | 'warn' | 'failed' | 'idle';

export interface RadarDot {
  id: string;
  name: string;
  x: number;
  y: number;
  r: number;
  tone: DotTone;
  /** "240 ms", "3 h ago", "timed out": what the label under the dot says. */
  detail: string;
  left: boolean;
}

/** Colour = last check: filled live answer, hollow index, amber slow or limited, red failed, grey not checked. */
export function toneOf(s: SourceInfo): DotTone {
  if (s.lastOutcome === 'FAILED') return 'failed';
  if (s.lastOutcome === 'TIMED_OUT' || s.lastOutcome === 'RATE_LIMITED') return 'warn';
  if (s.mode === 'CURATED' || !s.lastOutcome || s.lastOutcome === 'SKIPPED') return 'idle';
  return s.mode === 'LIVE' ? 'live' : 'index';
}

/** Where in its band a dot sits, 0 (inner edge) to 1: faster answer or fresher check = closer. */
function distanceOf(s: SourceInfo, now: number): number {
  if (s.mode === 'LIVE')
    return s.responseTimeMs == null ? 1 : Math.min(1, s.responseTimeMs / SLOW_MS);
  const checked = Date.parse(s.lastCheckedAt ?? '');
  return Number.isNaN(checked) ? 1 : Math.min(1, Math.max(0, now - checked) / OLD_MS);
}

/** Size = new this week from that source, when GET /sources says it; else every dot the same size. */
function radiusOf(s: SourceInfo): number {
  const added = newThisWeek(s);
  return added === null ? DOT : 4 + Math.min(8, Math.sqrt(added) * 1.6);
}

/**
 * One dot per source, each in a fixed slot: sources alternate left and right in the order GET /sources lists them,
 * and move only inside their band when their data changes.
 */
export function placeDots(
  sources: SourceInfo[],
  now: number,
  detail: (s: SourceInfo, tone: DotTone) => string,
): RadarDot[] {
  const perSide = Math.max(1, Math.ceil(sources.length / 2));
  return sources.map((s, i) => {
    const left = i % 2 === 0;
    const slot = Math.floor(i / 2);
    // Slots from the top of the band to the bottom; the dot sits on its ring at that height.
    const dy = -RISE + ((slot + 0.5) / perSide) * (RISE + FALL);
    const [from, to] = s.mode === 'LIVE' ? BANDS.inner : BANDS.outer;
    const radius = from + (to - from) * distanceOf(s, now);
    const tone = toneOf(s);
    return {
      id: s.id,
      name: s.name,
      x: CX + (left ? -1 : 1) * Math.sqrt(radius * radius - dy * dy),
      y: CY + dy,
      r: radiusOf(s),
      tone,
      detail: detail(s, tone),
      left,
    };
  });
}

function useDetail() {
  const { t, i18n } = useTranslation();
  const ago = new Intl.RelativeTimeFormat(i18n.language, { numeric: 'auto', style: 'narrow' });
  return (s: SourceInfo, tone: DotTone, now: number): string => {
    if (tone === 'failed') return t('radar.dot.failed');
    if (s.lastOutcome === 'TIMED_OUT') return t('radar.dot.timedOut');
    if (s.lastOutcome === 'RATE_LIMITED') return t('radar.dot.rateLimited');
    if (s.mode === 'LIVE')
      return s.responseTimeMs == null
        ? ''
        : responseTimeLabel(s.responseTimeMs, i18n.language).short;
    const checked = Date.parse(s.lastCheckedAt ?? '');
    if (Number.isNaN(checked)) return '';
    const minutes = Math.max(0, Math.round((now - checked) / 60_000));
    return minutes < 60
      ? ago.format(-minutes, 'minute')
      : ago.format(-Math.round(minutes / 60), 'hour');
  };
}

/**
 * The radar behind the home title (ADR 0042): decoration, hidden from screen readers; the Sources page shows the same
 * data as a table. It sweeps once when the page opens (4.5 s), then stays still. Below 900 px only the rings show.
 */
export function HomeRadar({ sources }: { sources: SourceInfo[] }) {
  const detail = useDetail();
  // Ages are read against the moment the page opened; the radar does not tick.
  const [now] = useState(Date.now);
  const dots = placeDots(sources, now, (s, tone) => detail(s, tone, now));
  return (
    <svg
      className="v4-radar"
      viewBox={`0 0 ${W} ${H}`}
      preserveAspectRatio="xMidYMid slice"
      aria-hidden="true"
      focusable="false"
    >
      <g className="v4-radar__rings">
        {RINGS.map((r) => (
          <circle key={r} cx={CX} cy={CY} r={r} />
        ))}
      </g>
      <g className="v4-radar__sweep" style={{ transformOrigin: `${CX}px ${CY}px` }}>
        <path d={`M${CX} ${CY} L${CX + 640} ${CY} A640 640 0 0 0 ${CX + 453} ${CY - 453} Z`} />
      </g>
      <g className="v4-radar__dots">
        {dots.map((d) => (
          <g key={d.id} className={`v4-dot v4-dot--${d.tone}`}>
            <circle cx={d.x} cy={d.y} r={d.r} />
            <text x={d.x} y={d.y + d.r + 14} textAnchor="middle" className="v4-dot__name">
              {d.name}
            </text>
            {d.detail && (
              <text x={d.x} y={d.y + d.r + 28} textAnchor="middle" className="v4-dot__detail">
                {d.detail}
              </text>
            )}
          </g>
        ))}
      </g>
    </svg>
  );
}
