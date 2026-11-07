import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { decide } from '@/lib/googleConsent';
import { axeViolations } from '@/test/a11y';
import { mockApi, problem } from '@/test/mockApi';
import { renderAdmin, renderApp, settle } from '@/test/render';
import { CONSENT_KEY } from '@/lib/consent';
import { ANON, makeDetail, makeSearch } from '@/test/fixtures';

const ON = {
  githubSponsorsUrl: 'https://github.com/sponsors/someone',
  stripePaymentUrl: 'https://buy.stripe.com/abc',
  ga4MeasurementId: 'G-TEST1234',
  ethicalAdsPublisher: 'helper2',
  adsenseClient: 'ca-pub-4688630163076563',
  adsenseSlot: '1234567890',
};
const OFF = {
  githubSponsorsUrl: null,
  stripePaymentUrl: null,
  ga4MeasurementId: null,
  ethicalAdsPublisher: null,
  adsenseClient: null,
  adsenseSlot: null,
};
const me = (monetisation: Partial<Record<keyof typeof ON, string | null>>) => () => ({
  ...ANON,
  monetisation: { ...OFF, ...monetisation },
});

const footer = () => screen.getByRole('contentinfo');
const banner = () => screen.queryByRole('region', { name: /Google Analytics/ });

/** Each test starts with no third-party state: a late effect of the previous test can add a script after it ends. */
function clearThirdParties() {
  document.getElementById('h2-ga4')?.remove();
  document.getElementById('h2-ethicalads')?.remove();
  delete window.gtag;
  delete window.dataLayer;
  delete window['ga-disable-G-TEST1234'];
  delete window.ethicalads;
  delete window.__tcfapi;
  delete window.googlefc;
  delete window.adsbygoogle;
  for (const id of ['h2-googlefc', 'h2-adsense']) document.getElementById(id)?.remove();
  document.querySelector('iframe[name="googlefcPresent"]')?.remove();
}
beforeEach(clearThirdParties);
afterEach(clearThirdParties);

/** Google's consent tool as the page sees it: the TCF API, answering with [tc] now and on [answer]. */
/**
 * Google's consent tool: the TCF API, and consent mode, whose analytics storage status (Google's number: 1 granted,
 * 2 denied) follows the TCF answer, as the tool sets both from one question.
 */
function fakeTcf(tc: Record<string, unknown>) {
  let listener: ((d: Record<string, unknown>, ok: boolean) => void) | undefined;
  let current = tc;
  window.googlefc = {
    ...window.googlefc,
    getGoogleConsentModeValues: () => ({
      analyticsStoragePurposeConsentStatus: decide(current) === 'granted' ? 1 : 2,
    }),
  };
  window.__tcfapi = (command, _version, callback) => {
    if (command !== 'addEventListener') return;
    listener = callback as typeof listener;
    listener?.({ listenerId: 1, ...current }, true);
  };
  return {
    answer: (next: Record<string, unknown>) =>
      act(() => {
        current = next;
        listener?.({ listenerId: 1, ...next }, true);
      }),
  };
}

const ADSENSE = {
  adsenseClient: ON.adsenseClient,
  adsenseSlot: ON.adsenseSlot,
  ga4MeasurementId: 'G-TEST1234',
};
const YES = {
  gdprApplies: true,
  eventStatus: 'useractioncomplete',
  purpose: { consents: { 1: true, 8: true } },
  vendor: { consents: { 755: true } },
};

describe('monetisation (ADR 0030)', () => {
  it('while everything is off: no support link, no cookie question, no ad, no third-party script', async () => {
    mockApi({ 'GET /me': me(OFF) });
    renderApp('/search?q=agents');
    await settle();
    expect(
      within(footer()).queryByRole('link', { name: 'Support helper2' }),
    ).not.toBeInTheDocument();
    expect(
      within(footer()).queryByRole('button', { name: 'Cookie settings' }),
    ).not.toBeInTheDocument();
    expect(banner()).not.toBeInTheDocument();
    expect(screen.queryByRole('complementary', { name: 'Sponsored' })).not.toBeInTheDocument();
    expect(document.querySelector('script[src^="https://"]')).toBeNull();
  });

  it('asks before Google Analytics, with equal buttons, and loads nothing on "Reject"', async () => {
    mockApi({ 'GET /me': me({ ga4MeasurementId: 'G-TEST1234' }) });
    renderApp('/');
    const question = await screen.findByRole('region', { name: /Google Analytics/ });
    // The radar loads on demand: wait for it, so it does not finish during the slow accessibility check below.
    await screen.findByRole('list', { name: 'Some of the sources we search' });
    await settle();
    const reject = within(question).getByRole('button', { name: 'Reject' });
    const accept = within(question).getByRole('button', { name: 'Accept' });
    expect(reject.className).toBe(accept.className);
    expect(document.getElementById('h2-ga4')).toBeNull();
    expect(await axeViolations(question)).toEqual([]);

    await userEvent.click(reject);

    expect(banner()).not.toBeInTheDocument();
    expect(document.getElementById('h2-ga4')).toBeNull();
    expect(JSON.parse(window.localStorage.getItem(CONSENT_KEY) ?? '{}')).toMatchObject({
      analytics: false,
    });
  });

  it('"Accept" loads Google Analytics; "Cookie settings" asks again', async () => {
    mockApi({ 'GET /me': me({ ga4MeasurementId: 'G-TEST1234' }) });
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Accept' }));
    expect(document.getElementById('h2-ga4')).not.toBeNull();

    await userEvent.click(within(footer()).getByRole('button', { name: 'Cookie settings' }));
    expect(banner()).toBeInTheDocument();
  });

  it('a withdrawal in another tab stops analytics in this one', async () => {
    mockApi({ 'GET /me': me({ ga4MeasurementId: 'G-TEST1234' }) });
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Accept' }));
    expect(window['ga-disable-G-TEST1234']).toBe(false);

    // The other tab writes its answer; this tab only gets the storage event.
    window.localStorage.setItem(
      CONSENT_KEY,
      JSON.stringify({ v: 1, analytics: false, at: '2026-11-05T22:00:00Z' }),
    );
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: CONSENT_KEY }));
    });

    expect(window['ga-disable-G-TEST1234']).toBe(true);
    expect(banner()).not.toBeInTheDocument();
  });

  it('never asks or loads on the back office', async () => {
    window.localStorage.setItem(
      CONSENT_KEY,
      JSON.stringify({ v: 1, analytics: true, at: '2026-11-05T00:00:00Z' }),
    );
    mockApi({ 'GET /me': me({ ga4MeasurementId: 'G-TEST1234' }) });
    renderAdmin('/admin');
    await settle();
    expect(banner()).not.toBeInTheDocument();
    expect(document.getElementById('h2-ga4')).toBeNull();
  });

  it('a search shows one labelled ad slot with why it is there, and the bar only while loading', async () => {
    let answer: (() => void) | undefined;
    mockApi({
      'GET /me': me({ ethicalAdsPublisher: 'helper2' }),
      'GET /search': async () => {
        await new Promise<void>((r) => (answer = r));
        const { makeSearch } = await import('@/test/fixtures');
        return makeSearch();
      },
    });
    renderApp('/search?q=agents');
    const slot = await screen.findByRole('complementary', { name: 'Sponsored' });
    expect(slot).toHaveTextContent('Ads keep helper2 free');
    expect(within(slot).getByRole('progressbar')).toBeInTheDocument();
    expect(screen.getAllByRole('complementary', { name: 'Sponsored' })).toHaveLength(1);
    // The slot shows on render; the client script is added by its effect, which can run a moment later.
    await waitFor(() => expect(document.getElementById('h2-ethicalads')).not.toBeNull());

    await waitFor(() => expect(answer).toBeDefined());
    answer?.();
    await waitFor(() => expect(within(slot).queryByRole('progressbar')).not.toBeInTheDocument());
    expect(screen.getAllByRole('complementary', { name: 'Sponsored' })).toHaveLength(1);
  });

  it('each new search empties the box and loads a new ad (the client skips manual boxes on reload)', async () => {
    const load = vi.fn((): void => {
      const box = document.getElementById('h2-results-ad');
      box?.classList.add('loaded');
      box?.append(document.createElement('a'));
    });
    const reload = vi.fn();
    window.ethicalads = Object.assign({ load }, { reload });
    mockApi({ 'GET /me': me({ ethicalAdsPublisher: 'helper2' }) });
    renderApp('/search?q=agents');
    await waitFor(() => expect(load).toHaveBeenCalledTimes(1));
    const box = document.getElementById('h2-results-ad');
    expect(box).toHaveClass('loaded');

    const input = screen.getByRole('textbox', { name: 'Search AI resources' });
    await userEvent.clear(input);
    await userEvent.type(input, 'mcp servers{Enter}');

    await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
    expect(reload).not.toHaveBeenCalled();
    // The same box, filled once: emptied before the second load.
    expect(document.getElementById('h2-results-ad')).toBe(box);
    expect(box?.children).toHaveLength(1);
  });

  it('no ad when the search fails', async () => {
    mockApi({
      'GET /me': me({ ethicalAdsPublisher: 'helper2' }),
      'GET /search': () => problem(503, 'Unavailable'),
    });
    renderApp('/search?q=agents');
    await screen.findByRole('alert');
    expect(screen.queryByRole('complementary', { name: 'Sponsored' })).not.toBeInTheDocument();
  });

  it('no ad when no source could search', async () => {
    mockApi({
      'GET /me': me({ ethicalAdsPublisher: 'helper2' }),
      'GET /search': () =>
        makeSearch({
          results: [],
          total: 0,
          coverage: {
            partial: true,
            sourcesConfigured: 1,
            sourcesQueried: 1,
            sourcesSucceeded: 0,
            note: 'n',
          },
          notices: [{ code: 'ALL_SOURCES_FAILED', message: 'All sources failed.' }],
        }),
    });
    renderApp('/search?q=agents');
    expect(await screen.findByRole('alert')).toHaveTextContent('No source responded');
    expect(screen.queryByRole('complementary', { name: 'Sponsored' })).not.toBeInTheDocument();
  });

  it('no ad next to a guide answer', async () => {
    mockApi({ 'GET /me': me({ ethicalAdsPublisher: 'helper2' }) });
    renderApp(
      '/search?q=which+agent+framework+should+I+use&ask=which+agent+framework+should+I+use',
    );
    await settle();
    expect(screen.queryByRole('complementary', { name: 'Sponsored' })).not.toBeInTheDocument();
  });

  it('the Support page offers only the ways that are on, and the footer links to it', async () => {
    mockApi({ 'GET /me': me({ githubSponsorsUrl: ON.githubSponsorsUrl }) });
    renderApp('/support');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Support helper2' }),
    ).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: /Sponsor on GitHub/ })).toHaveAttribute(
      'href',
      ON.githubSponsorsUrl,
    );
    expect(screen.queryByRole('link', { name: 'Give by card' })).not.toBeInTheDocument();
    expect(within(footer()).getByRole('link', { name: 'Support helper2' })).toHaveAttribute(
      'href',
      '/support',
    );
  });

  it('the Support page says when giving is not open', async () => {
    mockApi({ 'GET /me': me(OFF) });
    renderApp('/support');
    expect(await screen.findByText('Giving is not open yet.')).toBeInTheDocument();
  });

  describe('with AdSense on (ADR 0030)', () => {
    it("loads Google's consent tool instead of our bar, and the footer offers Privacy choices", async () => {
      fakeTcf({ gdprApplies: true, eventStatus: 'cmpuishown' });
      mockApi({ 'GET /me': me(ADSENSE) });
      renderApp('/');
      await waitFor(() => expect(document.getElementById('h2-googlefc')).not.toBeNull());
      expect((document.getElementById('h2-googlefc') as HTMLScriptElement).src).toBe(
        'https://fundingchoicesmessages.google.com/i/pub-4688630163076563?ers=1',
      );
      expect(banner()).not.toBeInTheDocument();
      expect(document.getElementById('h2-ga4')).toBeNull();
      await userEvent.click(within(footer()).getByRole('button', { name: 'Privacy choices' }));
      expect(
        window.googlefc?.callbackQueue?.filter((entry) => entry.CONSENT_DATA_READY),
      ).toHaveLength(1);
    });

    it('Privacy choices on a search address opens the Privacy page in the app, then asks there', async () => {
      fakeTcf({ gdprApplies: true, eventStatus: 'cmpuishown' });
      mockApi({ 'GET /me': me(ADSENSE) });
      const { router } = renderApp('/search?q=agents');
      await userEvent.click(
        await within(footer()).findByRole('button', { name: 'Privacy choices' }),
      );
      await waitFor(() => expect(router.state.location.pathname).toBe('/privacy'));
      expect(router.state.location.search).toBe('');
      expect(
        window.googlefc?.callbackQueue?.filter((entry) => entry.CONSENT_DATA_READY),
      ).toHaveLength(1);
      await settle();
    });

    it('in Europe, Google Analytics loads only after a yes, and a withdrawal turns it off', async () => {
      const tcf = fakeTcf({ gdprApplies: true, eventStatus: 'cmpuishown' });
      mockApi({ 'GET /me': me(ADSENSE) });
      renderApp('/');
      await waitFor(() => expect(document.getElementById('h2-googlefc')).not.toBeNull());
      expect(document.getElementById('h2-ga4')).toBeNull();

      tcf.answer(YES);
      await waitFor(() => expect(document.getElementById('h2-ga4')).not.toBeNull());

      tcf.answer({ ...YES, purpose: { consents: { 1: true, 8: false } } });
      await waitFor(() => expect(window['ga-disable-G-TEST1234']).toBe(true));
    });

    it('where the rules do not apply, Google Analytics loads without a question', async () => {
      fakeTcf({ gdprApplies: false, eventStatus: 'tcloaded' });
      mockApi({ 'GET /me': me(ADSENSE) });
      renderApp('/');
      await waitFor(() => expect(document.getElementById('h2-ga4')).not.toBeNull());
    });

    it('a resource page shows one labelled AdSense box with the publisher and the ad unit', async () => {
      fakeTcf({ gdprApplies: false, eventStatus: 'tcloaded' });
      const id = '11111111-1111-5111-8111-111111111111';
      mockApi({ 'GET /me': me(ADSENSE), [`GET /resources/${id}`]: () => makeDetail() });
      renderApp(`/resource/${id}`);
      const box = await screen.findByRole('complementary', { name: 'Sponsored' });
      const ins = box.querySelector('ins.adsbygoogle');
      expect(ins).toHaveAttribute('data-ad-client', 'ca-pub-4688630163076563');
      expect(ins).toHaveAttribute('data-ad-slot', '1234567890');
      expect((document.getElementById('h2-adsense') as HTMLScriptElement).src).toBe(
        'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-4688630163076563',
      );
      expect(window.adsbygoogle).toHaveLength(1);
      expect(screen.getAllByRole('complementary', { name: 'Sponsored' })).toHaveLength(1);
      // Google's consent tool goes in before the ad script, so the ad request waits for the consent signal.
      const consentTag = document.getElementById('h2-googlefc');
      const adTag = document.getElementById('h2-adsense');
      expect(consentTag).not.toBeNull();
      expect(document.querySelector('iframe[name="googlefcPresent"]')).not.toBeNull();
      expect(
        consentTag!.compareDocumentPosition(adTag!) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    });

    it("Google's tag does not load on an address with search words", async () => {
      mockApi({ 'GET /me': me(ADSENSE) });
      renderApp('/search?q=agents');
      await settle();
      expect(document.getElementById('h2-googlefc')).toBeNull();
      expect(document.getElementById('h2-ga4')).toBeNull();
    });

    it('leaving a resource page pauses AdSense requests', async () => {
      fakeTcf({ gdprApplies: false, eventStatus: 'tcloaded' });
      const id = '11111111-1111-5111-8111-111111111111';
      mockApi({ 'GET /me': me(ADSENSE), [`GET /resources/${id}`]: () => makeDetail() });
      const { router } = renderApp(`/resource/${id}`);
      await screen.findByRole('complementary', { name: 'Sponsored' });
      await waitFor(() => expect(window.adsbygoogle?.pauseAdRequests).toBe(0));

      await act(() => router.navigate('/search?q=agents'));
      await waitFor(() => expect(window.adsbygoogle?.pauseAdRequests).toBe(1));
    });

    it('a resource page shows one labelled AdSense box; a search page shows no AdSense', async () => {
      fakeTcf({ gdprApplies: false, eventStatus: 'tcloaded' });
      mockApi({ 'GET /me': me({ ...ADSENSE, ethicalAdsPublisher: 'helper2' }) });
      renderApp('/search?q=agents');
      await screen.findByRole('complementary', { name: 'Sponsored' });
      expect(document.querySelector('ins.adsbygoogle')).toBeNull();
      expect(document.getElementById('h2-adsense')).toBeNull();
    });
  });
});
