type Gtag = (...args: unknown[]) => void;

declare global {
  interface Window {
    dataLayer?: unknown[];
    gtag?: Gtag;
    [gaDisable: `ga-disable-${string}`]: boolean | undefined;
  }
}

const SCRIPT_ID = 'h2-ga4';

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

export function cleanLocation(origin: string, pathname: string): string {
  return origin + pathname;
}

export function cleanReferrer(referrer: string): string {
  try {
    const url = new URL(referrer);
    return url.origin + url.pathname;
  } catch {
    return '';
  }
}

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

export function cookieDomains(hostname: string): string[] {
  const parts = hostname.split('.');
  if (parts.length < 2) return [];
  return parts.slice(0, -1).map((_, i) => parts.slice(i).join('.'));
}
