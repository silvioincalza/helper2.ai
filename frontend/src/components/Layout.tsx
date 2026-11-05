import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import {
  NavLink,
  Outlet,
  Link,
  ScrollRestoration,
  useLocation,
  useNavigate,
  useSearchParams,
} from 'react-router';
import { useDropServerHead } from '@/lib/head';
import { useTranslation } from 'react-i18next';
import { signOut } from '@/api/client';
import { useMe } from '@/context/MeContext';
import { chooseTheme, currentTheme, otherTheme, subscribeTheme } from '@/lib/theme';
import { hasOwnSearchBox, showsHome } from '@/lib/searchState';
import { takeSignInReturn } from '@/lib/signInReturn';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { Logo } from './Logo';
import { OneBox } from './OneBox';
import '@/styles/v4.css';
import { SignInLink } from './SignIn';

/** The theme switch: a text button that names the theme it switches to ("Dark theme"). Light is the default. */
function ThemeToggle() {
  const { t } = useTranslation();
  const theme = useSyncExternalStore(subscribeTheme, currentTheme);
  const next = otherTheme(theme);
  return (
    <button type="button" className="btn-text theme-toggle" onClick={() => chooseTheme(next)}>
      {t('nav.theme.switchTo', { theme: t(`nav.theme.${next}`) })}
    </button>
  );
}

/** Up to this width the header is the logo and a menu button; the pages, sign-in and the theme open in a panel. */
export const PHONE_HEADER = '(max-width: 720px)';

function MenuButton({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  const { t } = useTranslation();
  return (
    <button
      type="button"
      className="btn btn-icon menu-toggle"
      aria-expanded={open}
      aria-controls="site-menu"
      aria-label={t('nav.menu')}
      onClick={onToggle}
    >
      <svg
        viewBox="0 0 24 24"
        width="20"
        height="20"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        aria-hidden="true"
        focusable="false"
      >
        {open ? <path d="M6 6l12 12M18 6L6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
      </svg>
    </button>
  );
}

/** Sign in, or the account link and Sign out. The admin page has its own sign-in. */
function Account() {
  const { t } = useTranslation();
  const { me } = useMe();
  const { pathname } = useLocation();
  if (!me.authenticated)
    return pathname.startsWith('/admin') ? null : <SignInLink className="btn" />;
  return (
    <span className="auth">
      <Link to="/account" className="v4-nav-link" title={t('nav.account')}>
        {me.displayName ?? t('nav.signedIn')}
      </Link>
      <button type="button" className="btn" onClick={() => void signOut()}>
        {t('nav.signOut')}
      </button>
    </span>
  );
}

/**
 * The header in design v4 (ADR 0042): logo with tagline, then News, Events, Saved and Sign in. On a phone (up to
 * 720 px) the logo and a menu button; the menu holds New this week, Coming up, Saved, Sources, Mission, Sign in and the
 * theme. Escape closes it and gives focus back to the button; moving to another page closes it. Search is the home
 * page; on the other pages (not Results, which has its own) the box sits in the header.
 */
function Header() {
  const { t } = useTranslation();
  const { isAdmin } = useMe();
  const { pathname } = useLocation();
  const phone = useMediaQuery(PHONE_HEADER, false);
  const onHome = hasOwnSearchBox(pathname);
  // Open on the page it was opened on: moving to another page closes it without an effect.
  const [openOn, setOpenOn] = useState<string | null>(null);
  const open = phone && openOn === pathname;
  const toggle = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setOpenOn(null);
      toggle.current?.querySelector<HTMLButtonElement>('.menu-toggle')?.focus();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open]);
  // A hash link keeps the pathname, so it closes the menu itself.
  const close = () => setOpenOn(null);
  const lists = phone
    ? { news: t('board.news.title'), events: t('board.events.title') }
    : { news: t('nav.news'), events: t('nav.events') };
  const nav = (
    <nav aria-label={t('nav.main')} className="v4-nav">
      <ul className="plain-list">
        <li>
          <Link className="v4-nav-link" to={{ pathname: '/', hash: 'news' }} onClick={close}>
            {lists.news}
          </Link>
        </li>
        <li>
          <Link className="v4-nav-link" to={{ pathname: '/', hash: 'events' }} onClick={close}>
            {lists.events}
          </Link>
        </li>
        <li>
          <NavLink className="v4-nav-link" to="/saved">
            {t('nav.saved')}
          </NavLink>
        </li>
        {phone && (
          <>
            <li>
              <NavLink className="v4-nav-link" to="/sources">
                {t('nav.sources')}
              </NavLink>
            </li>
            <li>
              <NavLink className="v4-nav-link" to="/mission">
                {t('nav.mission')}
              </NavLink>
            </li>
          </>
        )}
        {isAdmin && (
          <li>
            <NavLink className="v4-nav-link" to="/admin">
              {t('nav.admin')}
            </NavLink>
          </li>
        )}
      </ul>
    </nav>
  );
  return (
    <header className="v4 site-header">
      <div className="v4-wrap v4-header__row">
        <Link to="/" className="logo-link" aria-label={t('nav.home')}>
          <Logo />
        </Link>
        {phone ? (
          <>
            <div className="v4-header__quick" ref={toggle}>
              <MenuButton open={open} onToggle={() => setOpenOn(open ? null : pathname)} />
            </div>
            <div id="site-menu" className={open ? 'v4-menu is-open' : 'v4-menu'}>
              {nav}
              <div className="v4-menu__tools">
                <Account />
                <ThemeToggle />
              </div>
            </div>
          </>
        ) : (
          <>
            {nav}
            <div className="v4-header__tools">
              <Account />
            </div>
          </>
        )}
      </div>
      {!onHome && (
        <div className="v4-wrap v4-header__box">
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
    <svg
      className="icon"
      viewBox="0 0 16 16"
      width="14"
      height="14"
      fill="currentColor"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
    </svg>
  );
}

/** An external link: opens in a new tab, says so to screen readers, and ends with ↗. */
function OutLink({ href, label, children }: { href: string; label: string; children: ReactNode }) {
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" aria-label={label}>
      {children}
      <span aria-hidden="true"> ↗</span>
    </a>
  );
}

/**
 * The footer in design v4 (Main board): the logo, one row of links (the pages, the code, the release, the theme),
 * then one line: what is searched, no ads, who and where.
 */
function Footer() {
  const { t } = useTranslation();
  return (
    <footer className="v4 site-footer">
      <div className="v4-wrap v4-footer__row">
        <Link to="/" className="logo-link" aria-label={t('nav.home')}>
          <Logo />
        </Link>
        <ul className="plain-list v4-footer__links">
          <li>
            <Link to="/mission">{t('footer.mission')}</Link>
          </li>
          <li>
            <Link to="/sources">{t('nav.sources')}</Link>
          </li>
          <li>
            <Link to="/privacy">{t('footer.privacy')}</Link>
          </li>
          <li>
            <Link to="/terms">{t('footer.terms')}</Link>
          </li>
          <li>
            <OutLink href={REPO_URL} label={t('footer.githubLabel')}>
              <GitHubMark />
              {t('footer.github')}
            </OutLink>
          </li>
          <li>
            <OutLink href={`${REPO_URL}/blob/main/LICENSE`} label={t('footer.licenseLabel')}>
              {t('footer.license')}
            </OutLink>
          </li>
          <li>
            <OutLink
              href={`${REPO_URL}/releases/tag/v${__APP_VERSION__}`}
              label={t('footer.versionLabel', { version: __APP_VERSION__ })}
            >
              v{__APP_VERSION__}
            </OutLink>
          </li>
          <li>
            <ThemeToggle />
          </li>
        </ul>
      </div>
      {/* No claim about tracking, so the footer stays true when tracking cookies come (ADR 0040, 9). Where it is run
          and hosted, as the Privacy page states it (Hosting: Germany). */}
      <p className="v4-wrap v4-footer__line">
        {t('footer.coverage')} <span className="footer-no-ads">{t('footer.noAds')}</span>{' '}
        {t('footer.copyright', { year: new Date().getFullYear() })}
        <span className="footer-place"> · {t('footer.place')}</span>
      </p>
    </footer>
  );
}

/**
 * After a move to another page (a new pathname, not new search params), focus goes to the page's h1, else to
 * #main, and a polite live region names the page: keyboard and screen-reader users start at the new content.
 */
function RouteFocus() {
  useDropServerHead();
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

/**
 * After GitHub sends a signed-in visitor back, goes to the page where they clicked "Sign in" (ADR 0035). A failed
 * sign-in (?signin=failed) drops the stored page, says so and links to the sign-in page.
 */
function SignInReturn() {
  const { t } = useTranslation();
  const { me, loaded } = useMe();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { pathname } = useLocation();
  const failed = params.get('signin') === 'failed';
  useEffect(() => {
    if (!loaded || (!me.authenticated && !failed)) return;
    const path = takeSignInReturn();
    if (me.authenticated && path) navigate(path, { replace: true });
  }, [loaded, me.authenticated, failed, navigate]);
  // The admin page says it in its own words.
  return failed && !pathname.startsWith('/admin') ? (
    <p role="alert" className="banner banner-warn">
      {t('nav.signInFailed')}{' '}
      {me.loginProviders.length > 0 && <Link to="/signin">{t('nav.signInAgain')}</Link>}
    </p>
  ) : null;
}

export function Layout() {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const [sp] = useSearchParams();
  // Home spans the full width (the radar, the pinned box); the other pages sit in the header's column.
  const home = showsHome(pathname, sp);
  return (
    <>
      <a className="skip-link" href="#main">
        {t('nav.skip')}
      </a>
      {/* A new page starts at the top; back and forward return to where the reader was. */}
      <ScrollRestoration />
      <RouteFocus />
      <Header />
      <main id="main" tabIndex={-1} className={home ? 'main main--v4' : 'v4-wrap main'}>
        <SignInReturn />
        <Outlet />
      </main>
      <Footer />
    </>
  );
}
