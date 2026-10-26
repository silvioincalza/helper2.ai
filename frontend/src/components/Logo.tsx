import { useTranslation } from 'react-i18next';

/** The radar mark: a sweep that has locked onto a found blip, an AI spark, on an accent tile. Strokes follow --on-accent, so it works in both themes. */
export function LogoMark() {
  return (
    <span className="logo-mark">
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
        strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
        <circle cx="12" cy="12" r="9" />
        <circle cx="12" cy="12" r="4.2" strokeWidth="1.4" opacity="0.5" />
        <path d="M12 12 18.4 5.6" />
        <path d="M16.2 11.1Q16.2 15 20.1 15Q16.2 15 16.2 18.9Q16.2 15 12.3 15Q16.2 15 16.2 11.1Z"
          fill="currentColor" strokeWidth="1" />
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
        <span className="logo-word">helper2.ai</span>
        <span className="logo-tagline">{t('logo.tagline')}</span>
      </span>
    </span>
  );
}
