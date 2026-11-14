import { AiKey } from '@/ui/AiLabel';
import { upTo, upToHeight } from '@/lib/breakpoints';
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
import { useMonetisation } from '@/context/MonetisationContext';
import { AnalyticsConsent, openConsentSettings } from '../features/monetisation/AnalyticsConsent';
import { showPrivacyChoices } from '@/lib/googleConsent';
import { chooseTheme, currentTheme, otherTheme, subscribeTheme } from '@/lib/theme';
import { hasOwnSearchBox, showsHome } from '@/lib/searchState';
import { takeSignInReturn } from '@/lib/signInReturn';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useStickyEdge } from '@/hooks/useStickyEdge';
import { Logo } from '../ui/Logo';
import { OneBox } from '../features/search/OneBox';
import '@/styles/shell.css';
import { SignInLink } from '../features/account/SignIn';

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

const PHONE_HEADER = upTo('small');
const SHORT = upToHeight('short');

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

function Account() {
  const { t } = useTranslation();
  const { me } = useMe();
  if (!me.authenticated) return <SignInLink className="btn" />;
  return (
    <span className="auth">
      <Link to="/account" className="h2-nav-link" title={t('nav.account')}>
        {me.displayName ?? t('nav.signedIn')}
      </Link>
      <button type="button" className="btn" onClick={() => void signOut()}>
        {t('nav.signOut')}
      </button>
    </span>
  );
}

function Header() {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const phone = useMediaQuery(PHONE_HEADER, false);
  const onHome = hasOwnSearchBox(pathname);
  const [openOn, setOpenOn] = useState<string | null>(null);
  const open = phone && openOn === pathname;
  const toggle = useRef<HTMLDivElement>(null);
  const header = useRef<HTMLElement>(null);
  useStickyEdge('top', header, { sticks: !useMediaQuery(SHORT, false) });
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
  const lists = { news: t('nav.news'), events: t('nav.events') };
  const nav = (
    <nav aria-label={t('nav.main')} className="h2-nav">
      <ul className="plain-list">
        <li>
          <NavLink className="h2-nav-link" to="/news">
            {lists.news}
          </NavLink>
        </li>
        <li>
          <NavLink className="h2-nav-link" to="/events">
            {lists.events}
          </NavLink>
        </li>
        <li>
          <NavLink className="h2-nav-link" to="/saved">
            {t('nav.saved')}
          </NavLink>
        </li>
        {phone && (
          <>
            <li>
              <NavLink className="h2-nav-link" to="/sources">
                {t('nav.sources')}
              </NavLink>
            </li>
            <li>
              <NavLink className="h2-nav-link" to="/mission">
                {t('nav.mission')}
              </NavLink>
            </li>
          </>
        )}
      </ul>
    </nav>
  );
  return (
    <header ref={header} className="h2-app site-header">
      <div className="h2-wrap h2-header__row">
        <Link to="/" className="logo-link" aria-label={t('nav.home')}>
          <Logo />
        </Link>
        {phone ? (
          <>
            <div className="h2-header__quick" ref={toggle}>
              <MenuButton open={open} onToggle={() => setOpenOn(open ? null : pathname)} />
            </div>
            <div id="site-menu" className={open ? 'h2-menu is-open' : 'h2-menu'}>
              {nav}
              <div className="h2-menu__tools">
                <Account />
                <ThemeToggle />
              </div>
            </div>
          </>
        ) : (
          <>
            {nav}
            <div className="h2-header__tools">
              <Account />
            </div>
          </>
        )}
      </div>
      {!onHome && (
        <div className="h2-wrap h2-header__box">
          <OneBox />
        </div>
      )}
    </header>
  );
}

export const REPO_URL = 'https://github.com/silvioincalza/helper2.ai';

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

function OutLink({ href, label, children }: { href: string; label: string; children: ReactNode }) {
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" aria-label={label}>
      {children}
      <span aria-hidden="true"> ↗</span>
    </a>
  );
}

function Footer() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { search } = useLocation();
  const { githubSponsorsUrl, stripePaymentUrl, ga4MeasurementId, adsenseClient } =
    useMonetisation();
  const support = githubSponsorsUrl !== null || stripePaymentUrl !== null;
  return (
    <footer className="h2-app site-footer">
      <div className="h2-wrap h2-footer__row">
        <Link to="/" className="logo-link" aria-label={t('nav.home')}>
          <Logo />
        </Link>
        <ul className="plain-list h2-footer__links">
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
          {support && (
            <li>
              <Link to="/support">{t('footer.support')}</Link>
            </li>
          )}
          {adsenseClient !== null ? (
            <li>
              <button
                type="button"
                className="h2-text-btn"
                onClick={() => void showPrivacyChoices(search, navigate)}
              >
                {t('footer.privacyChoices')}
              </button>
            </li>
          ) : (
            ga4MeasurementId !== null && (
              <li>
                <button type="button" className="h2-text-btn" onClick={openConsentSettings}>
                  {t('footer.cookieSettings')}
                </button>
              </li>
            )
          )}
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
      <p className="h2-wrap h2-footer__line">
        {t('footer.coverage')} {t('footer.copyright', { year: new Date().getFullYear() })}
        <span className="footer-place"> · {t('footer.place')}</span>
      </p>
      <p className="h2-wrap h2-footer__line">
        <AiKey />
      </p>
    </footer>
  );
}

function ConsentSlot() {
  const slot = useRef<HTMLDivElement>(null);
  useStickyEdge('bottom', slot, { sticks: !useMediaQuery(SHORT, false) });
  return (
    <div ref={slot} className="h2-consent-slot">
      <AnalyticsConsent />
    </div>
  );
}

function RouteFocus() {
  useDropServerHead();
  const { pathname } = useLocation();
  const live = useRef<HTMLParagraphElement>(null);
  const previous = useRef<string | null>(null);
  useEffect(() => {
    const before = previous.current;
    previous.current = pathname;
    if (before === null || sameResource(before, pathname)) return;
    const main = document.getElementById('main');
    const h1 = main?.querySelector('h1');
    if (h1) h1.tabIndex = -1;
    (h1 ?? main)?.focus({ preventScroll: true });
    if (live.current) live.current.textContent = h1?.textContent?.trim() || document.title;
  }, [pathname]);
  return <p ref={live} className="sr-only" aria-live="polite" aria-atomic="true" />;
}

const RESOURCE_ID = /^\/resource\/([^/]+)/;

function sameResource(a: string, b: string): boolean {
  const id = RESOURCE_ID.exec(a)?.[1];
  return id !== undefined && id === RESOURCE_ID.exec(b)?.[1];
}

function SignInReturn() {
  const { t } = useTranslation();
  const { me, loaded, signInAgain } = useMe();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const failed = params.get('signin') === 'failed';
  useEffect(() => {
    if (!loaded || (!me.authenticated && !failed)) return;
    const path = takeSignInReturn();
    if (me.authenticated && path) navigate(path, { replace: true });
  }, [loaded, me.authenticated, failed, navigate]);
  return failed || signInAgain ? (
    <p role="alert" className="banner banner-warn">
      {t(failed ? 'nav.signInFailed' : 'nav.sessionEnded')}{' '}
      {me.loginProviders.length > 0 && <Link to="/signin">{t('nav.signInAgain')}</Link>}
    </p>
  ) : null;
}

export function Layout() {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const [sp] = useSearchParams();
  const home = showsHome(pathname, sp);
  return (
    <>
      <a className="skip-link" href="#main">
        {t('nav.skip')}
      </a>
      <ConsentSlot />
      <RouteFocus />
      <Header />
      <main id="main" tabIndex={-1} className={home ? 'main main--v4' : 'h2-wrap main'}>
        <SignInReturn />
        <Outlet />
      </main>
      <Footer />
      <ScrollRestoration />
    </>
  );
}
