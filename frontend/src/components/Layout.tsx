import { useEffect, useRef, useSyncExternalStore, type ReactNode } from 'react';
import { NavLink, Outlet, Link, useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import { signOut } from '@/api/client';
import { useMe } from '@/context/MeContext';
import { LANGUAGES, setLanguage } from '@/i18n';
import { chooseTheme, currentChoice, nextChoice, subscribeTheme, type ThemeChoice } from '@/lib/theme';
import { SAVED_ENABLED } from '@/lib/features';
import { Logo } from './Logo';
import { OneBox } from './OneBox';

/** Each choice's 18px stroke icon: the device for "follow system", else the sun or the moon. */
const THEME_ICON: Record<ThemeChoice, ReactNode> = {
  system: <><rect x="3" y="4" width="18" height="12" rx="2" /><path d="M8 20h8M12 16v4" /></>,
  light: <><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></>,
  dark: <path d="M20 14.5A8 8 0 019.5 4a8 8 0 1010.5 10.5z" />,
};

/** One button that shows the current theme choice and moves to the next: follow system → light → dark. */
function ThemeToggle() {
  const { t } = useTranslation();
  const choice = useSyncExternalStore(subscribeTheme, currentChoice);
  const next = nextChoice(choice);
  const label = t('nav.theme.label', { current: t(`nav.theme.${choice}`), next: t(`nav.theme.${next}`) });
  return (
    <button type="button" className="btn btn-icon theme-toggle" aria-label={label} title={label} onClick={() => chooseTheme(next)}>
      <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"
        strokeLinejoin="round" aria-hidden="true" focusable="false">
        {THEME_ICON[choice]}
      </svg>
    </button>
  );
}

export function Header() {
  const { t, i18n } = useTranslation();
  const { me, isAdmin } = useMe();
  // The home page has the box as its hero; every other page keeps it in the header.
  const onHome = useLocation().pathname === '/';
  return (
    <header className="site-header">
      <div className="container header-row">
        <Link to="/" className="logo-link" aria-label={t('nav.home')}><Logo /></Link>
        {/* Search is the home page (the logo goes there); the menu only lists the pages that are on. */}
        {(SAVED_ENABLED || isAdmin) && (
          <nav aria-label={t('nav.main')} className="main-nav">
            <ul className="plain-list">
              {SAVED_ENABLED && <li><NavLink to="/saved">{t('nav.saved')}</NavLink></li>}
              {isAdmin && <li><NavLink to="/admin">{t('nav.admin')}</NavLink></li>}
            </ul>
          </nav>
        )}
        <div className="header-tools">
          <ThemeToggle />
          {LANGUAGES.length > 1 && (
            <label className="lang">
              <span className="sr-only">{t('nav.language')}</span>
              <select value={i18n.resolvedLanguage ?? 'en'} onChange={(e) => setLanguage(e.target.value)}>
                {LANGUAGES.map((l) => <option key={l.code} value={l.code} lang={l.code}>{l.label}</option>)}
              </select>
            </label>
          )}
          {me.authenticated && (
            <span className="auth">
              <span className="muted small">{me.displayName ?? t('nav.signedIn')}</span>
              <button type="button" className="btn" onClick={() => void signOut()}>{t('nav.signOut')}</button>
            </span>
          )}
        </div>
      </div>
      {!onHome && (
        <div className="container header-box">
          <OneBox />
        </div>
      )}
    </header>
  );
}

export const REPO_URL = 'https://github.com/silvioincalza/helper2.ai';

/** The GitHub mark is a filled glyph, so it can't go through the stroke-only Icon. */
function GitHubMark() {
  return (
    <svg className="icon" viewBox="0 0 16 16" width="14" height="14" fill="currentColor" aria-hidden="true" focusable="false">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
    </svg>
  );
}

export function Footer() {
  const { t } = useTranslation();
  return (
    <footer className="site-footer">
      {/* Columns: what Helper2.ai is (and where its results come from), its pages, its code; then the fine print. */}
      <div className="container footer-grid">
        <div className="footer-brand">
          <Link to="/" className="logo-link" aria-label={t('nav.home')}><Logo /></Link>
          <p className="footer-coverage small">{t('footer.coverage')} <Link to="/sources">{t('nav.sources')}</Link></p>
        </div>
        <div className="footer-col">
          <h2 className="footer-h">{t('footer.colSite')}</h2>
          <ul className="plain-list">
            <li><Link to="/mission">{t('footer.mission')}</Link></li>
            <li><Link to="/privacy">{t('footer.privacy')}</Link></li>
          </ul>
        </div>
        <div className="footer-col">
          <h2 className="footer-h">{t('footer.colCode')}</h2>
          <ul className="plain-list">
            <li>
              <a href={REPO_URL} target="_blank" rel="noopener noreferrer" aria-label={t('footer.githubLabel')}>
                <GitHubMark />{t('footer.github')}
              </a>
            </li>
            <li>
              <a href={`${REPO_URL}/blob/main/LICENSE`} target="_blank" rel="noopener noreferrer" aria-label={t('footer.licenseLabel')}>
                {t('footer.license')}
              </a>
            </li>
            <li>
              <a href={`${REPO_URL}/releases/tag/v${__APP_VERSION__}`} target="_blank" rel="noopener noreferrer" aria-label={t('footer.versionLabel', { version: __APP_VERSION__ })}>
                v{__APP_VERSION__}
              </a>
            </li>
          </ul>
        </div>
      </div>
      <div className="container footer-bottom small">{t('footer.copyright', { year: new Date().getFullYear() })}</div>
    </footer>
  );
}

/**
 * After a move to another page (a new pathname, not new search params), focus goes to the page's h1, else to
 * #main, and a polite live region names the page: keyboard and screen-reader users start at the new content.
 */
function RouteFocus() {
  const { pathname } = useLocation();
  const live = useRef<HTMLParagraphElement>(null);
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    const main = document.getElementById('main');
    const h1 = main?.querySelector('h1');
    if (h1) h1.tabIndex = -1;
    (h1 ?? main)?.focus();
    if (live.current) live.current.textContent = h1?.textContent?.trim() || document.title;
  }, [pathname]);
  return <p ref={live} className="sr-only" aria-live="polite" aria-atomic="true" />;
}

export function Layout() {
  const { t } = useTranslation();
  return (
    <>
      <a className="skip-link" href="#main">{t('nav.skip')}</a>
      <RouteFocus />
      <Header />
      <main id="main" tabIndex={-1} className="container main">
        <Outlet />
      </main>
      <Footer />
    </>
  );
}
