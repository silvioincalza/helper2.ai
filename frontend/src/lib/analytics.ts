/**
 * Google Analytics 4, only after the visitor accepts (ADR 0057). What reaches Google is the page path and a fixed
 * page name: never the search words, the guide question or the page title (which holds the search words), never on
 * the back office. Google signals and ad personalisation are off.
 */

type Gtag = (...args: unknown[]) => void;

declare global {
  interface Window {
    dataLayer?: unknown[];
    gtag?: Gtag;
    /** Google's per-stream off switch: `ga-disable-<measurement ID>`. */
    [gaDisable: `ga-disable-${string}`]: boolean | undefined;
  }
}

const SCRIPT_ID = 'h2-ga4';

/** Fixed names per route; a resource page is "Resource", whatever its title. */
export function pageName(pathname: string): string {
  if (pathname === '/') return 'Home';
  const first = pathname.split('/')[1] ?? '';
  const names: Record<string, string> = {
    search: 'Search',
    resource: 'Resource',
    saved: 'Saved',
    sources: 'Sources',
    account: 'Account',
    mission: 'Mission',
    privacy: 'Privacy',
    terms: 'Terms',
    support: 'Support',
  };
  return names[first] ?? 'Other';
}

/** The page address Google sees: origin and path, no query string and no fragment. */
export function cleanLocation(origin: string, pathname: string): string {
  return origin + pathname;
}

/**
 * The referrer Google sees: origin and path, never a query string. Google reads `document.referrer` by default, and
 * after a move inside the site that is a search address with `q` and `ask`. Empty when there is none or it is not a
 * URL.
 */
export function cleanReferrer(referrer: string): string {
  try {
    const url = new URL(referrer);
    return url.origin + url.pathname;
  } catch {
    return '';
  }
}

/** The clean address of the last page view: the referrer of the next one. */
let lastLocation = '';

export function isAnalyticsPath(pathname: string): boolean {
  return pathname !== '/admin' && !pathname.startsWith('/admin/');
}

export function startAnalytics(measurementId: string, doc: Document = document): void {
  window[`ga-disable-${measurementId}`] = false;
  if (doc.getElementById(SCRIPT_ID)) {
    window.gtag?.('consent', 'update', { analytics_storage: 'granted' });
    return;
  }
  window.dataLayer = window.dataLayer ?? [];
  // gtag must push the arguments object itself, as Google's snippet does.
  window.gtag = function gtag() {
    // eslint-disable-next-line prefer-rest-params
    window.dataLayer?.push(arguments);
  };
  window.gtag('consent', 'default', {
    analytics_storage: 'granted',
    ad_storage: 'denied',
    ad_user_data: 'denied',
    ad_personalization: 'denied',
  });
  window.gtag('js', new Date());
  window.gtag('config', measurementId, {
    send_page_view: false,
    // Host-only cookies: on helper2.ai itself, never on a parent domain (staging is helper2.incalza.me).
    cookie_domain: 'none',
    allow_google_signals: false,
    allow_ad_personalization_signals: false,
    page_location: cleanLocation(window.location.origin, window.location.pathname),
    page_referrer: cleanReferrer(doc.referrer),
    page_title: pageName(window.location.pathname),
  });
  const script = doc.createElement('script');
  script.id = SCRIPT_ID;
  script.async = true;
  script.src = `https://www.googletagmanager.com/gtag/js?id=${encodeURIComponent(measurementId)}`;
  doc.head.appendChild(script);
}

/**
 * One page view; `set` first, so any later event Google sends on its own carries the clean address and referrer
 * too. The referrer is the previous clean address, or the clean `document.referrer` on the first view.
 */
export function trackPage(pathname: string, doc: Document = document): void {
  const location = cleanLocation(window.location.origin, pathname);
  const page = {
    page_location: location,
    page_referrer: lastLocation || cleanReferrer(doc.referrer),
    page_title: pageName(pathname),
  };
  lastLocation = location;
  window.gtag?.('set', page);
  window.gtag?.('event', 'page_view', page);
}

/**
 * After "Reject" or a withdrawn consent: Google's off switch for the stream (denied storage alone still lets
 * cookieless pings through), and Google's cookies on our domain are removed.
 */
export function stopAnalytics(measurementId: string, doc: Document = document): void {
  window[`ga-disable-${measurementId}`] = true;
  window.gtag?.('consent', 'update', { analytics_storage: 'denied' });
  const names = doc.cookie
    .split(';')
    .map((c) => c.split('=')[0]?.trim() ?? '')
    .filter((name) => name === '_ga' || name.startsWith('_ga_'));
  for (const name of names) {
    const gone = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
    doc.cookie = gone;
    for (const domain of cookieDomains(window.location.hostname))
      doc.cookie = `${gone}; domain=${domain}`;
  }
}

/**
 * The host and each parent domain a cookie for this host can live on, without the top-level domain:
 * helper2.incalza.me → helper2.incalza.me, incalza.me. Our cookies are host-only, but a cookie Google set on a
 * parent domain is removed too.
 */
export function cookieDomains(hostname: string): string[] {
  const parts = hostname.split('.');
  if (parts.length < 2) return [];
  return parts.slice(0, -1).map((_, i) => parts.slice(i).join('.'));
}
