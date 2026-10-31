import { useEffect, useRef, useSyncExternalStore, type ReactNode } from 'react';
import { NavLink, Outlet, Link, ScrollRestoration, useLocation } from 'react-router';
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

/** The bookmark glyph of the Saved button. */
const SAVED_ICON = <path d="M6 3h12v18l-6-4-6 4z" />;

/**
 * Design v2 header. On the home landing (an empty box) the hero carries the logo and the box, so the header is one
 * quiet row of links and the theme button. Everywhere else: the logo with its tagline, the one box, then the tools.
 */
export function Header() {
  const { t, i18n } = useTranslation();
  const { me, isAdmin } = useMe();
  const { pathname, search } = useLocation();
  const sp = new URLSearchParams(search);
  const landing = pathname === '/' && !sp.get('q') && !sp.get('ask');
  // On the home page with a search, the box sits on the page itself, under the header (HomePage).
  const boxHere = pathname !== '/';

  const tools = (
    <div className="header-tools">
      {isAdmin && <NavLink to="/admin" className="nav-a">{t('nav.admin')}</NavLink>}
      <ThemeToggle />
      {SAVED_ENABLED && (
        <Link to="/saved" className="btn btn-icon" aria-label={t('nav.saved')} title={t('nav.saved')}>
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round"
            aria-hidden="true" focusable="false">{SAVED_ICON}</svg>
        </Link>
      )}
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
          <Link to="/account" className="nav-a" title={t('nav.account')}>{me.displayName ?? t('nav.signedIn')}</Link>
          <button type="button" className="btn" onClick={() => void signOut()}>{t('nav.signOut')}</button>
        </span>
      )}
    </div>
  );

  if (landing) {
    return (
      <header className="site-header is-landing">
        <nav aria-label={t('nav.main')} className="container header-row">
          {SAVED_ENABLED && <NavLink to="/saved" className="nav-a">{t('nav.saved')}</NavLink>}
          <NavLink to="/sources" className="nav-a">{t('nav.sources')}</NavLink>
          <NavLink to="/mission" className="nav-a">{t('nav.mission')}</NavLink>
          {tools}
        </nav>
      </header>
    );
  }
  return (
    <header className="site-header">
      <div className="container header-row">
        <Link to="/" className="logo-link" aria-label={t('nav.home')}><Logo /></Link>
        {boxHere && <div className="header-box"><OneBox /></div>}
        {tools}
      </div>
    </header>
  );
}

export const REPO_URL = 'https://github.com/silvioincalza/helper2.ai';

/** One row: who and where, the site's pages, the code and its version, and the one promise. */
export function Footer() {
  const { t } = useTranslation();
  return (
    <footer className="site-footer">
      <div className="container footer-row">
        <span>{t('footer.place')}</span>
        <Link to="/mission">{t('footer.mission')}</Link>
        <Link to="/sources">{t('footer.sources')}</Link>
        <Link to="/privacy">{t('footer.privacy')}</Link>
        <Link to="/terms">{t('footer.terms')}</Link>
        <a href={REPO_URL} target="_blank" rel="noopener noreferrer" aria-label={t('footer.githubLabel')}>{t('footer.github')} <span aria-hidden="true">↗</span></a>
        <a href={`${REPO_URL}/releases/tag/v${__APP_VERSION__}`} target="_blank" rel="noopener noreferrer"
          aria-label={t('footer.versionLabel', { version: __APP_VERSION__ })}>v{__APP_VERSION__}</a>
        <span className="footer-end">{t('footer.noAds')}</span>
      </div>
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
    // ScrollRestoration owns the scroll position (top for a new page, the old place on back and forward).
    (h1 ?? main)?.focus({ preventScroll: true });
    if (live.current) live.current.textContent = h1?.textContent?.trim() || document.title;
  }, [pathname]);
  return <p ref={live} className="sr-only" aria-live="polite" aria-atomic="true" />;
}

export function Layout() {
  const { t } = useTranslation();
  return (
    <>
      <a className="skip-link" href="#main">{t('nav.skip')}</a>
      {/* A new page starts at the top; back and forward return to where the reader was. */}
      <ScrollRestoration />
      <RouteFocus />
      <Header />
      <main id="main" tabIndex={-1} className="container main">
        <Outlet />
      </main>
      <Footer />
    </>
  );
}
