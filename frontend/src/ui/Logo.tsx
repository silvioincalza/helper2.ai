import { useTranslation } from 'react-i18next';

const S_PATH = 'M19.5 44C24 50.5 41 50.5 41.5 41C42 30.5 21 32.5 22.5 23.5C24 15 37.5 15 41 19.5';

/**
 * The mark: an ink tile, the "s" drawn as one search path from the bottom up with its route dashed
 * in the accent, and the four-point spark where the path ends. Colours are the theme's tokens, so
 * the tile turns light in the dark theme.
 */
function LogoMark() {
  return (
    <span className="logo-mark">
      <svg width="40" height="40" viewBox="0 0 64 64" aria-hidden="true" focusable="false">
        <rect width="64" height="64" rx="19" fill="var(--ink)" />
        <circle
          cx="32"
          cy="32"
          r="14"
          fill="none"
          stroke="var(--bg)"
          strokeWidth="1.1"
          opacity="0.35"
        />
        <circle
          cx="32"
          cy="32"
          r="25.5"
          fill="none"
          stroke="var(--bg)"
          strokeWidth="1.1"
          opacity="0.35"
        />
        <path
          d={S_PATH}
          fill="none"
          stroke="var(--bg)"
          strokeWidth="7"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <path
          d="M49.5 5.5Q49.5 13.5 57.5 13.5Q49.5 13.5 49.5 21.5Q49.5 13.5 41.5 13.5Q49.5 13.5 49.5 5.5Z"
          fill="var(--accent)"
        />
        <path
          d={S_PATH}
          fill="none"
          stroke="var(--accent)"
          strokeWidth="1"
          strokeDasharray="2.2 2"
        />
        <circle cx="19.5" cy="44" r="2" fill="var(--accent)" />
        <circle cx="41" cy="19.5" r="2" fill="var(--accent)" />
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
