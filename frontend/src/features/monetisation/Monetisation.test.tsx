import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { decide } from '@/lib/googleConsent';
import { axeViolations } from '@/test/a11y';
import { mockApi, problem } from '@/test/mockApi';
import { renderAdmin, renderApp, settle } from '@/test/render';
import { CONSENT_KEY } from '@/lib/consent';
import { makeDetail, makeSearch } from '@/test/fixtures';

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
const site = (monetisation: Partial<Record<keyof typeof ON, string | null>>) => () => ({
  monetisation: { ...OFF, ...monetisation },
});

const footer = () => screen.getByRole('contentinfo');
const banner = () => screen.queryByRole('region', { name: /Google Analytics/ });

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

describe('monetisation (ADR 0042)', () => {
  it('while everything is off: no support link, no cookie question, no ad, no third-party script', async () => {
    mockApi({ 'GET /site': site(OFF) });
    renderApp('/search?q=agents');
    await settle();
    expect(
      within(footer()).queryByRole('link', { name: 'Support helper2.ai' }),
    ).not.toBeInTheDocument();
    expect(
      within(footer()).queryByRole('button', { name: 'Cookie settings' }),
    ).not.toBeInTheDocument();
    expect(banner()).not.toBeInTheDocument();
    expect(screen.queryByRole('complementary', { name: 'Sponsored' })).not.toBeInTheDocument();
    expect(document.querySelector('script[src^="https://"]')).toBeNull();
  });

  it('asks before Google Analytics, with equal buttons, and loads nothing on "Reject"', async () => {
    mockApi({ 'GET /site': site({ ga4MeasurementId: 'G-TEST1234' }) });
    renderApp('/');
    const question = await screen.findByRole('region', { name: /Google Analytics/ });
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

  it('Scenario: the question ends its own sentence, names the footer button that is there, and its link says where it goes', async () => {
    mockApi({ 'GET /site': site({ ga4MeasurementId: 'G-TEST1234' }) });
    renderApp('/');
    const question = await screen.findByRole('region', { name: /Google Analytics/ });
    expect(question).toHaveTextContent(
      'You can change your answer in Cookie settings in the footer. How we use analytics',
    );
    expect(within(question).getByRole('link', { name: 'How we use analytics' })).toHaveAttribute(
      'href',
      '/privacy#cookies-and-browser-storage',
    );
    expect(within(footer()).getByRole('button', { name: 'Cookie settings' })).toBeInTheDocument();
  });

  it('"Accept" loads Google Analytics; "Cookie settings" asks again', async () => {
    mockApi({ 'GET /site': site({ ga4MeasurementId: 'G-TEST1234' }) });
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Accept' }));
    expect(document.getElementById('h2-ga4')).not.toBeNull();

    await userEvent.click(within(footer()).getByRole('button', { name: 'Cookie settings' }));
    expect(banner()).toBeInTheDocument();
  });

  it('a withdrawal in another tab stops analytics in this one', async () => {
    mockApi({ 'GET /site': site({ ga4MeasurementId: 'G-TEST1234' }) });
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Accept' }));
    expect(window['ga-disable-G-TEST1234']).toBe(false);

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
    const { callsTo } = mockApi({ 'GET /site': site({ ga4MeasurementId: 'G-TEST1234' }) });
    renderAdmin('/admin');
    await settle();
    expect(banner()).not.toBeInTheDocument();
    expect(document.getElementById('h2-ga4')).toBeNull();
    expect(callsTo('GET', '/site')).toHaveLength(0);
  });

  it('the settings are read once from GET /site, not from the session', async () => {
    const { callsTo } = mockApi({ 'GET /site': site({ githubSponsorsUrl: ON.githubSponsorsUrl }) });
    const { router } = renderApp('/');
    await settle();
    await act(() => router.navigate('/support'));
    await settle();
    expect(callsTo('GET', '/site')).toHaveLength(1);
    expect(within(footer()).getByRole('link', { name: 'Support helper2.ai' })).toBeInTheDocument();
  });

  it('while GET /site fails everything stays off', async () => {
    mockApi({ 'GET /site': () => problem(503, 'Service unavailable') });
    renderApp('/search?q=agents');
    await settle();
    expect(
      within(footer()).queryByRole('link', { name: 'Support helper2.ai' }),
    ).not.toBeInTheDocument();
    expect(banner()).not.toBeInTheDocument();
    expect(
      document.querySelectorAll('script[src*="google"], script[src*="ethicalads"]'),
    ).toHaveLength(0);
  });

  describe('Feature: one ad per search (ADR 0042)', () => {
    function fakeEthicalAds() {
      const load = vi.fn((): void => {
        const box = document.getElementById('h2-results-ad');
        box?.classList.add('loaded');
        box?.append(document.createElement('a'));
      });
      const reload = vi.fn();
      window.ethicalads = Object.assign({ load }, { reload });
      return { load, reload };
    }

    it('Scenario: a search shows one labelled ad slot with why it is there, the bar only while it runs, and the ad when the results are there', async () => {
      let answer: (() => void) | undefined;
      mockApi({
        'GET /site': site({ ethicalAdsPublisher: 'helper2' }),
        'GET /search': async () => {
          await new Promise<void>((r) => (answer = r));
          return makeSearch();
        },
      });
      renderApp('/search?q=agents');
      const slot = await screen.findByRole('complementary', { name: 'Sponsored' });
      expect(slot).toHaveTextContent('Ads keep helper2.ai free');
      expect(within(slot).getByRole('progressbar')).toBeInTheDocument();
      expect(screen.getAllByRole('complementary', { name: 'Sponsored' })).toHaveLength(1);
      await waitFor(() => expect(answer).toBeDefined());
      await settle();
      expect(document.getElementById('h2-ethicalads')).toBeNull();

      answer?.();
      await waitFor(() => expect(within(slot).queryByRole('progressbar')).not.toBeInTheDocument());
      await waitFor(() => expect(document.getElementById('h2-ethicalads')).not.toBeNull());
      expect(screen.getAllByRole('complementary', { name: 'Sponsored' })).toHaveLength(1);
    });

    it('Scenario: a filter, a sort or another view of the same words keeps the ad', async () => {
      const { load } = fakeEthicalAds();
      const { callsTo } = mockApi({ 'GET /site': site({ ethicalAdsPublisher: 'helper2' }) });
      const { router } = renderApp('/search?q=agents');
      await waitFor(() => expect(load).toHaveBeenCalledTimes(1));
      const box = document.getElementById('h2-results-ad');
      const ad = box?.firstElementChild;

      for (const address of [
        '/search?q=agents&type=MODEL',
        '/search?q=agents&type=MODEL&sort=NEWEST',
        '/search?q=agents&view=timeline',
      ]) {
        await act(() => router.navigate(address));
        await settle();
      }

      expect(callsTo('GET', '/search').length).toBeGreaterThan(1);
      expect(load).toHaveBeenCalledTimes(1);
      expect(document.getElementById('h2-results-ad')).toBe(box);
      expect(box?.firstElementChild).toBe(ad);
    });

    it('Scenario: words that changed before their results came get no ad', async () => {
      const { load } = fakeEthicalAds();
      const waiting: (() => void)[] = [];
      mockApi({
        'GET /site': site({ ethicalAdsPublisher: 'helper2' }),
        'GET /search': async () => {
          await new Promise<void>((r) => waiting.push(r));
          return makeSearch();
        },
      });
      const { router } = renderApp('/search?q=age');
      await waitFor(() => expect(waiting).toHaveLength(1));
      await act(() => router.navigate('/search?q=agents', { replace: true }));
      await waitFor(() => expect(waiting).toHaveLength(2));
      expect(load).not.toHaveBeenCalled();

      await act(async () => waiting.forEach((r) => r()));
      await waitFor(() => expect(load).toHaveBeenCalledTimes(1));
      await settle();
      expect(load).toHaveBeenCalledTimes(1);
    });

    it('Scenario: each new search empties the box and loads a new ad (the client skips manual boxes on reload)', async () => {
      const { load, reload } = fakeEthicalAds();
      mockApi({ 'GET /site': site({ ethicalAdsPublisher: 'helper2' }) });
      renderApp('/search?q=agents');
      await waitFor(() => expect(load).toHaveBeenCalledTimes(1));
      const box = document.getElementById('h2-results-ad');
      expect(box).toHaveClass('loaded');

      const input = screen.getByRole('textbox', { name: 'Search AI resources' });
      await userEvent.clear(input);
      await userEvent.type(input, 'mcp servers{Enter}');

      await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
      expect(reload).not.toHaveBeenCalled();
      expect(document.getElementById('h2-results-ad')).toBe(box);
      expect(box?.children).toHaveLength(1);
    });

    it('Scenario: a search that fails asks for no ad', async () => {
      const { load } = fakeEthicalAds();
      let fail: (() => void) | undefined;
      mockApi({
        'GET /site': site({ ethicalAdsPublisher: 'helper2' }),
        'GET /search': async () => {
          await new Promise<void>((r) => (fail = r));
          return problem(503, 'Unavailable');
        },
      });
      renderApp('/search?q=agents');
      await screen.findByRole('complementary', { name: 'Sponsored' });
      await waitFor(() => expect(fail).toBeDefined());
      await settle();
      fail?.();
      await screen.findByRole('alert');
      expect(load).not.toHaveBeenCalled();
    });
  });

  it('no ad when the search fails', async () => {
    mockApi({
      'GET /site': site({ ethicalAdsPublisher: 'helper2' }),
      'GET /search': () => problem(503, 'Unavailable'),
    });
    renderApp('/search?q=agents');
    await screen.findByRole('alert');
    expect(screen.queryByRole('complementary', { name: 'Sponsored' })).not.toBeInTheDocument();
  });

  it('no ad when no source could search', async () => {
    mockApi({
      'GET /site': site({ ethicalAdsPublisher: 'helper2' }),
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
    mockApi({ 'GET /site': site({ ethicalAdsPublisher: 'helper2' }) });
    renderApp(
      '/search?q=which+agent+framework+should+I+use&ask=which+agent+framework+should+I+use',
    );
    await settle();
    expect(screen.queryByRole('complementary', { name: 'Sponsored' })).not.toBeInTheDocument();
  });

  it('the Support page offers only the ways that are on, and the footer links to it', async () => {
    mockApi({ 'GET /site': site({ githubSponsorsUrl: ON.githubSponsorsUrl }) });
    renderApp('/support');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Support helper2.ai' }),
    ).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: /Sponsor on GitHub/ })).toHaveAttribute(
      'href',
      ON.githubSponsorsUrl,
    );
    expect(screen.queryByRole('link', { name: 'Give by card' })).not.toBeInTheDocument();
    expect(within(footer()).getByRole('link', { name: 'Support helper2.ai' })).toHaveAttribute(
      'href',
      '/support',
    );
  });

  it('the Support page says when giving is not open', async () => {
    mockApi({ 'GET /site': site(OFF) });
    renderApp('/support');
    expect(await screen.findByText('Giving is not open yet.')).toBeInTheDocument();
  });

  describe('with AdSense on (ADR 0042)', () => {
    const RESOURCE = '/resource/11111111-1111-5111-8111-111111111111/acme-translator';
    const CONSENT_TAG = 'https://fundingchoicesmessages.google.com/i/pub-4688630163076563?ers=1';
    const withResource = (monetisation = ADSENSE) =>
      mockApi({
        'GET /site': site(monetisation),
        'GET /resources/11111111-1111-5111-8111-111111111111': () => makeDetail(),
      });
    const pageViews = () =>
      (window.dataLayer ?? [])
        .map((entry) => Array.from(entry as ArrayLike<unknown>))
        .filter(([command, name]) => command === 'event' && name === 'page_view')
        .map(([, , page]) => (page as { page_location: string }).page_location);

    describe("Feature: Google's consent question is asked on resource pages only", () => {
      it.each(['/', '/news', '/mission', '/privacy', '/search?q=agents'])(
        "Scenario: a first visit to %s asks nothing from Google's consent tool and counts nothing",
        async (address) => {
          fakeTcf({ gdprApplies: false, eventStatus: 'tcloaded' });
          mockApi({ 'GET /site': site(ADSENSE) });
          renderApp(address);
          await screen.findByRole('button', { name: 'Privacy choices' });
          await settle();
          expect(document.getElementById('h2-googlefc')).toBeNull();
          expect(document.querySelector('iframe[name="googlefcPresent"]')).toBeNull();
          expect(document.getElementById('h2-ga4')).toBeNull();
          expect(banner()).not.toBeInTheDocument();
        },
      );

      it("Scenario: a resource page loads Google's consent tool instead of our bar", async () => {
        fakeTcf({ gdprApplies: true, eventStatus: 'cmpuishown' });
        withResource();
        renderApp(RESOURCE);
        await waitFor(() => expect(document.getElementById('h2-googlefc')).not.toBeNull());
        expect((document.getElementById('h2-googlefc') as HTMLScriptElement).src).toBe(CONSENT_TAG);
        expect(banner()).not.toBeInTheDocument();
        expect(document.getElementById('h2-ga4')).toBeNull();
      });

      it('Scenario: in Europe, Google Analytics loads only after a yes, and a withdrawal turns it off', async () => {
        const tcf = fakeTcf({ gdprApplies: true, eventStatus: 'cmpuishown' });
        withResource();
        renderApp(RESOURCE);
        await waitFor(() => expect(document.getElementById('h2-googlefc')).not.toBeNull());
        expect(document.getElementById('h2-ga4')).toBeNull();

        tcf.answer(YES);
        await waitFor(() => expect(document.getElementById('h2-ga4')).not.toBeNull());

        tcf.answer({ ...YES, purpose: { consents: { 1: true, 8: false } } });
        await waitFor(() => expect(window['ga-disable-G-TEST1234']).toBe(true));
      });

      it('Scenario: where the rules do not apply, Google Analytics loads without a question', async () => {
        fakeTcf({ gdprApplies: false, eventStatus: 'tcloaded' });
        withResource();
        renderApp(RESOURCE);
        await waitFor(() => expect(document.getElementById('h2-ga4')).not.toBeNull());
      });

      it('Scenario: a visit that starts on Home is counted from its first resource page, however long Home was open', async () => {
        vi.useFakeTimers({ shouldAdvanceTime: true });
        try {
          withResource();
          const { router } = renderApp('/');
          await screen.findByRole('button', { name: 'Privacy choices' });
          await act(() => vi.advanceTimersByTimeAsync(11_000));
          expect(document.getElementById('h2-googlefc')).toBeNull();

          fakeTcf({ gdprApplies: false, eventStatus: 'tcloaded' });
          await act(() => router.navigate(RESOURCE));
          await waitFor(() => expect(document.getElementById('h2-googlefc')).not.toBeNull());
          await waitFor(() => expect(document.getElementById('h2-ga4')).not.toBeNull());
          expect(pageViews()).toEqual([window.location.origin + RESOURCE]);
        } finally {
          vi.useRealTimers();
        }
      });

      it('Scenario: after the answer on a resource page, later pages of the visit are counted, without search words', async () => {
        const tcf = fakeTcf({ gdprApplies: true, eventStatus: 'cmpuishown' });
        withResource();
        const { router } = renderApp(RESOURCE);
        await waitFor(() => expect(document.getElementById('h2-googlefc')).not.toBeNull());
        tcf.answer(YES);
        await waitFor(() => expect(pageViews()).toHaveLength(1));

        await act(() => router.navigate('/mission'));
        await act(() => router.navigate('/search?q=secret+words&ask=secret+question'));
        await settle();

        const origin = window.location.origin;
        expect(pageViews()).toEqual([origin + RESOURCE, `${origin}/mission`, `${origin}/search`]);
        expect(
          JSON.stringify(window.dataLayer?.map((e) => Array.from(e as ArrayLike<unknown>))),
        ).not.toContain('secret');
        expect(document.querySelectorAll('script#h2-googlefc')).toHaveLength(1);
      });

      it('Scenario: a "no" on a resource page keeps Google Analytics off on the later pages', async () => {
        const tcf = fakeTcf({ gdprApplies: true, eventStatus: 'cmpuishown' });
        withResource();
        const { router } = renderApp(RESOURCE);
        await waitFor(() => expect(document.getElementById('h2-googlefc')).not.toBeNull());
        tcf.answer({ ...YES, purpose: { consents: {} } });
        await act(() => router.navigate('/mission'));
        await settle();
        expect(document.getElementById('h2-ga4')).toBeNull();
      });
    });

    describe('Feature: "Privacy choices" in the footer works from any page', () => {
      it("Scenario: on Home it loads Google's consent tool on demand and asks", async () => {
        const tcf = fakeTcf({ gdprApplies: true, eventStatus: 'cmpuishown' });
        mockApi({ 'GET /site': site(ADSENSE) });
        renderApp('/');
        const choices = await screen.findByRole('button', { name: 'Privacy choices' });
        await settle();
        expect(document.getElementById('h2-googlefc')).toBeNull();

        await userEvent.click(choices);

        await waitFor(() => expect(document.getElementById('h2-googlefc')).not.toBeNull());
        expect((document.getElementById('h2-googlefc') as HTMLScriptElement).src).toBe(CONSENT_TAG);
        expect(
          window.googlefc?.callbackQueue?.filter((entry) => entry.CONSENT_DATA_READY),
        ).toHaveLength(1);
        const show = vi.fn();
        window.googlefc = { ...window.googlefc, showRevocationMessage: show };
        window.googlefc.callbackQueue?.find((e) => e.CONSENT_DATA_READY)?.CONSENT_DATA_READY?.();
        expect(show).toHaveBeenCalledOnce();

        expect(document.getElementById('h2-ga4')).toBeNull();
        tcf.answer(YES);
        await waitFor(() => expect(document.getElementById('h2-ga4')).not.toBeNull());
      });

      it('Scenario: on a search address it opens the Privacy page in the app first, then loads the tool and asks there', async () => {
        fakeTcf({ gdprApplies: true, eventStatus: 'cmpuishown' });
        mockApi({ 'GET /site': site(ADSENSE) });
        const { router } = renderApp('/search?q=agents');
        const seen: string[] = [];
        const added = new MutationObserver(() => {
          if (document.getElementById('h2-googlefc'))
            seen.push(router.state.location.pathname + router.state.location.search);
        });
        added.observe(document.head, { childList: true });
        await userEvent.click(
          await within(footer()).findByRole('button', { name: 'Privacy choices' }),
        );
        await waitFor(() => expect(document.getElementById('h2-googlefc')).not.toBeNull());
        await settle();
        added.disconnect();
        expect(router.state.location.pathname).toBe('/privacy');
        expect(router.state.location.search).toBe('');
        expect(new Set(seen)).toEqual(new Set(['/privacy']));
        expect(
          window.googlefc?.callbackQueue?.filter((entry) => entry.CONSENT_DATA_READY),
        ).toHaveLength(1);
      });
    });

    it('a resource page shows one labelled AdSense box with the publisher and the ad unit', async () => {
      fakeTcf({ gdprApplies: false, eventStatus: 'tcloaded' });
      const id = '11111111-1111-5111-8111-111111111111';
      mockApi({ 'GET /site': site(ADSENSE), [`GET /resources/${id}`]: () => makeDetail() });
      renderApp(`/resource/${id}`);
      const box = await screen.findByRole('complementary', { name: 'Sponsored' });
      const ins = box.querySelector('ins.adsbygoogle');
      expect(ins).toHaveAttribute('data-ad-client', 'ca-pub-4688630163076563');
      expect(ins).toHaveAttribute('data-ad-slot', '1234567890');
      await waitFor(() => expect(document.getElementById('h2-adsense')).not.toBeNull());
      expect((document.getElementById('h2-adsense') as HTMLScriptElement).src).toBe(
        'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-4688630163076563',
      );
      expect(window.adsbygoogle).toHaveLength(1);
      expect(screen.getAllByRole('complementary', { name: 'Sponsored' })).toHaveLength(1);
      const consentTag = document.getElementById('h2-googlefc');
      const adTag = document.getElementById('h2-adsense');
      expect(consentTag).not.toBeNull();
      expect(document.querySelector('iframe[name="googlefcPresent"]')).not.toBeNull();
      expect(
        consentTag!.compareDocumentPosition(adTag!) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    });

    it('leaving a resource page pauses AdSense requests', async () => {
      fakeTcf({ gdprApplies: false, eventStatus: 'tcloaded' });
      const id = '11111111-1111-5111-8111-111111111111';
      mockApi({ 'GET /site': site(ADSENSE), [`GET /resources/${id}`]: () => makeDetail() });
      const { router } = renderApp(`/resource/${id}`);
      await screen.findByRole('complementary', { name: 'Sponsored' });
      await waitFor(() => expect(window.adsbygoogle?.pauseAdRequests).toBe(0));

      await act(() => router.navigate('/search?q=agents'));
      await waitFor(() => expect(window.adsbygoogle?.pauseAdRequests).toBe(1));
    });

    it('a resource page shows one labelled AdSense box; a search page shows no AdSense', async () => {
      fakeTcf({ gdprApplies: false, eventStatus: 'tcloaded' });
      mockApi({ 'GET /site': site({ ...ADSENSE, ethicalAdsPublisher: 'helper2' }) });
      renderApp('/search?q=agents');
      await screen.findByRole('complementary', { name: 'Sponsored' });
      expect(document.querySelector('ins.adsbygoogle')).toBeNull();
      expect(document.getElementById('h2-adsense')).toBeNull();
    });
  });
});
