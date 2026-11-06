import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { CONSENT_KEY } from '@/lib/consent';
import { ANON, makeSearch } from '@/test/fixtures';

const ON = {
  githubSponsorsUrl: 'https://github.com/sponsors/someone',
  stripePaymentUrl: 'https://buy.stripe.com/abc',
  ga4MeasurementId: 'G-TEST1234',
  ethicalAdsPublisher: 'helper2',
};
const OFF = {
  githubSponsorsUrl: null,
  stripePaymentUrl: null,
  ga4MeasurementId: null,
  ethicalAdsPublisher: null,
};
const me = (monetisation: Partial<typeof ON> | typeof OFF) => () => ({
  ...ANON,
  monetisation: { ...OFF, ...monetisation },
});

const footer = () => screen.getByRole('contentinfo');
const banner = () => screen.queryByRole('region', { name: /Google Analytics/ });

afterEach(() => {
  document.getElementById('h2-ga4')?.remove();
  document.getElementById('h2-ethicalads')?.remove();
  delete window.gtag;
  delete window.dataLayer;
  delete window['ga-disable-G-TEST1234'];
  delete window.ethicalads;
});

describe('monetisation (ADR 0057)', () => {
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
    renderApp('/admin');
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
});
