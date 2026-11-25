import { useTranslation } from 'react-i18next';

const S_PATH = 'M19.5 44C24 50.5 41 50.5 41.5 41C42 30.5 21 32.5 22.5 23.5C24 15 37.5 15 41 19.5';
const STAR_PATH =
  'M53.65 15Q53.65 19.5 58.15 19.5Q53.65 19.5 53.65 24Q53.65 19.5 49.15 19.5Q53.65 19.5 53.65 15Z';

const CENTRE = 32;
const SCOPE = 30;
const RINGS = [10, 20, SCOPE];
const BEAM = (-15 * Math.PI) / 180; // half past two on a clock face
const TRAIL = (45 * Math.PI) / 180;
const TRAIL_SLICES = 12;
const TRAIL_STEP = TRAIL / TRAIL_SLICES;

function onScope(angle: number) {
  return `${(CENTRE + SCOPE * Math.cos(angle)).toFixed(2)} ${(CENTRE + SCOPE * Math.sin(angle)).toFixed(2)}`;
}

/** The trail the beam leaves behind it: 45° back to one o'clock, fading, drawn as slices of the scope. */
const TRAIL_PATHS = Array.from({ length: TRAIL_SLICES }, (_, i) => {
  return {
    d: `M${CENTRE} ${CENTRE}L${onScope(BEAM - TRAIL_STEP * i)}A${SCOPE} ${SCOPE} 0 0 0 ${onScope(BEAM - TRAIL_STEP * (i + 1))}Z`,
    opacity: (0.5 * (1 - i / TRAIL_SLICES)).toFixed(2),
  };
});

/**
 * The mark: an ink tile with a radar scope behind the "s". The beam sweeps clockwise to half past
 * two, its trail fading behind it, and the four-point spark sits at two o'clock. The "s" is one
 * search path drawn from the bottom up, its route dashed in the accent. Colours are the theme's
 * tokens, so the tile turns light in the dark theme.
 */
function LogoMark() {
  return (
    <span className="logo-mark">
      <svg width="40" height="40" viewBox="0 0 64 64" aria-hidden="true" focusable="false">
        <rect width="64" height="64" rx="19" fill="var(--ink)" />
        {RINGS.map((r) => (
          <circle
            key={r}
            cx={CENTRE}
            cy={CENTRE}
            r={r}
            fill="none"
            stroke="var(--bg)"
            strokeWidth="1"
            opacity="0.32"
          />
        ))}
        <path d="M2 32H62M32 2V62" stroke="var(--bg)" strokeWidth="0.8" opacity="0.22" />
        {TRAIL_PATHS.map(({ d, opacity }) => (
          <path key={d} d={d} fill="var(--accent)" opacity={opacity} />
        ))}
        <path
          d={`M${CENTRE} ${CENTRE}L${onScope(BEAM)}`}
          stroke="var(--accent)"
          strokeWidth="0.8"
          strokeLinecap="round"
          opacity="0.55"
        />
        <path
          d={S_PATH}
          fill="none"
          stroke="var(--bg)"
          strokeWidth="7"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <path d={STAR_PATH} fill="var(--accent)" />
        <path
          d={S_PATH}
          fill="none"
          stroke="var(--accent)"
          strokeWidth="0.8"
          strokeDasharray="2.2 2"
        />
        <circle cx="19.5" cy="44" r="1.5" fill="var(--accent)" />
        <circle cx="41" cy="19.5" r="1.5" fill="var(--accent)" />
      </svg>
    </span>
  );
}

export function Logo() {
  const { t } = useTranslation();
  return (
    <span className="logo">
      <LogoMark />
      <span className="logo-text">
        <span className="logo-word">
          search<span className="logo-avo">avo</span>
          <span className="logo-cursor" aria-hidden="true" />
        </span>
        <span className="logo-tag">{t('nav.tagline')}</span>
      </span>
    </span>
  );
}
