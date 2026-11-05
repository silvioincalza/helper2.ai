import {
  cleanLocation,
  cleanReferrer,
  cookieDomains,
  isAnalyticsPath,
  pageName,
  startAnalytics,
  stopAnalytics,
  trackPage,
} from './analytics';

describe('analytics (ADR 0057)', () => {
  afterEach(() => {
    document.getElementById('h2-ga4')?.remove();
    delete window.gtag;
    delete window.dataLayer;
    delete window['ga-disable-G-TEST1234'];
  });

  it('names pages by route, never by title or id', () => {
    expect(pageName('/')).toBe('Home');
    expect(pageName('/search')).toBe('Search');
    expect(pageName('/resource/abc-123')).toBe('Resource');
    expect(pageName('/support/thanks')).toBe('Support');
    expect(pageName('/whatever')).toBe('Other');
  });

  it('sends the path without the query string', () => {
    expect(cleanLocation('https://helper2.ai', '/search')).toBe('https://helper2.ai/search');
  });

  it('never runs on the back office', () => {
    expect(isAnalyticsPath('/admin')).toBe(false);
    expect(isAnalyticsPath('/admin/review')).toBe(false);
    expect(isAnalyticsPath('/administrator-tools')).toBe(true);
    expect(isAnalyticsPath('/search')).toBe(true);
  });

  it('loads gtag once, without its own page view, signals or ad personalisation', () => {
    startAnalytics('G-TEST1234');
    startAnalytics('G-TEST1234');

    const scripts = document.querySelectorAll('script#h2-ga4');
    expect(scripts).toHaveLength(1);
    expect((scripts[0] as HTMLScriptElement).src).toBe(
      'https://www.googletagmanager.com/gtag/js?id=G-TEST1234',
    );
    const config = window.dataLayer
      ?.map((a) => Array.from(a as ArrayLike<unknown>))
      .find((a) => a[0] === 'config');
    expect(config?.[2]).toMatchObject({
      send_page_view: false,
      allow_google_signals: false,
      allow_ad_personalization_signals: false,
      cookie_domain: 'none',
    });
  });

  it('a page view carries only the clean address and the page name', () => {
    window.history.pushState({}, '', '/search?q=secret+words');
    startAnalytics('G-TEST1234');
    trackPage('/search');

    const sent = JSON.stringify(window.dataLayer?.map((a) => Array.from(a as ArrayLike<unknown>)));
    expect(sent).not.toContain('secret');
    expect(sent).toContain('"page_location":"http://localhost:3000/search"');
    expect(sent).toContain('"page_title":"Search"');
    window.history.pushState({}, '', '/');
  });

  it('stopping removes the Google Analytics cookies', () => {
    document.cookie = '_ga=GA1.1.1; path=/';
    document.cookie = '_ga_TEST1234=GS1.1; path=/';
    document.cookie = 'keep=1; path=/';
    stopAnalytics('G-TEST1234');
    expect(document.cookie).not.toContain('_ga');
    expect(document.cookie).toContain('keep=1');
    document.cookie = 'keep=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  it('the referrer carries no query string', () => {
    expect(cleanReferrer('https://helper2.ai/search?q=secret&ask=private+question')).toBe(
      'https://helper2.ai/search',
    );
    expect(cleanReferrer('')).toBe('');
    expect(cleanReferrer('not a url')).toBe('');
  });

  it('page views send a clean referrer, also the one Google would read from the browser', () => {
    vi.spyOn(document, 'referrer', 'get').mockReturnValue(
      'http://localhost:3000/search?q=secret+words&ask=private+question',
    );
    startAnalytics('G-TEST1234');
    trackPage('/resource/abc');
    trackPage('/search');

    const calls = (window.dataLayer ?? []).map((a) => Array.from(a as ArrayLike<unknown>));
    const sent = JSON.stringify(calls);
    expect(sent).not.toContain('secret');
    expect(sent).not.toContain('private');
    const views = calls.filter((c) => c[0] === 'event' && c[1] === 'page_view');
    expect(views.map((v) => (v[2] as { page_referrer: string }).page_referrer)).toEqual([
      'http://localhost:3000/search',
      'http://localhost:3000/resource/abc',
    ]);
  });

  it('withdrawing turns the stream off, accepting again turns it back on', () => {
    startAnalytics('G-TEST1234');
    expect(window['ga-disable-G-TEST1234']).toBe(false);
    stopAnalytics('G-TEST1234');
    expect(window['ga-disable-G-TEST1234']).toBe(true);
    startAnalytics('G-TEST1234');
    expect(window['ga-disable-G-TEST1234']).toBe(false);
  });

  it('removes cookies on the host and on every parent domain, never on the top-level domain', () => {
    expect(cookieDomains('helper2.incalza.me')).toEqual(['helper2.incalza.me', 'incalza.me']);
    expect(cookieDomains('helper2.ai')).toEqual(['helper2.ai']);
    expect(cookieDomains('localhost')).toEqual([]);
  });
});
