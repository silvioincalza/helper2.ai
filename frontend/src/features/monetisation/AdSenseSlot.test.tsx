import { act, screen, waitFor, within } from '@testing-library/react';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { makeDetail, makeSearch } from '@/test/fixtures';

const ID = '11111111-1111-5111-8111-111111111111';
const RESOURCE = `/resource/${ID}/acme-translator`;
const CLIENT = 'ca-pub-4688630163076563';
const RESOURCE_UNIT = '1234567890';
const SEARCH_UNIT = '2345678901';
type Units = { adsenseSlot: string | null; adsenseSlotSearch: string | null };
const site = (units: Units) => () => ({
  monetisation: {
    githubSponsorsUrl: null,
    stripePaymentUrl: null,
    ga4MeasurementId: null,
    adsenseClient: CLIENT,
    ...units,
  },
});
const BOTH: Units = { adsenseSlot: RESOURCE_UNIT, adsenseSlotSearch: SEARCH_UNIT };
const withAdSense = (search: () => unknown = () => makeSearch(), units: Units = BOTH) =>
  mockApi({
    'GET /site': site(units),
    [`GET /resources/${ID}`]: () => makeDetail(),
    'GET /search': search,
  });
const adBox = () => screen.queryByRole('complementary', { name: 'Advertisements' });

function clearGoogle() {
  for (const id of ['h2-adsense', 'h2-googlefc']) document.getElementById(id)?.remove();
  document.querySelector('iframe[name="googlefcPresent"]')?.remove();
  delete window.adsbygoogle;
  delete window.googlefc;
}

function expectNoAd() {
  expect(adBox()).toBeNull();
  expect(document.getElementById('h2-adsense')).toBeNull();
  expect(document.querySelector('ins.adsbygoogle')).toBeNull();
}

function expectNothingFromGoogle() {
  expectNoAd();
  expect(document.getElementById('h2-googlefc')).toBeNull();
}

beforeEach(clearGoogle);
afterEach(clearGoogle);

describe('Feature: the AdSense slot on a resource page (ADR 0042)', () => {
  it("Scenario: a resource page loads Google's consent tool and the ad of its own ad unit", async () => {
    withAdSense();
    renderApp(RESOURCE);
    await waitFor(() => expect(document.getElementById('h2-adsense')).not.toBeNull());
    expect(document.getElementById('h2-googlefc')).not.toBeNull();
    expect(document.querySelector('ins.adsbygoogle')).toHaveAttribute(
      'data-ad-slot',
      RESOURCE_UNIT,
    );
  });

  it.each(['?q=agents', '?ask=which+one'])(
    'Scenario: a resource page opened with %s loads nothing from Google and shows no ad',
    async (search) => {
      withAdSense();
      renderApp(`${RESOURCE}${search}`);
      await screen.findByRole('heading', { level: 1 });
      await settle();
      expectNothingFromGoogle();
    },
  );

  it('Scenario: with only the search ad unit set, a resource page shows no ad and asks nothing', async () => {
    withAdSense(undefined, { adsenseSlot: null, adsenseSlotSearch: SEARCH_UNIT });
    renderApp(RESOURCE);
    await screen.findByRole('heading', { level: 1 });
    await settle();
    expectNothingFromGoogle();
  });
});

describe('Feature: the AdSense slot below the search results (ADR 0042)', () => {
  it("Scenario: a search with results shows one labelled box with the search ad unit, after Google's consent tool", async () => {
    withAdSense();
    renderApp('/search?q=agents');
    await waitFor(() => expect(adBox()).not.toBeNull());
    expect(screen.getAllByRole('complementary', { name: 'Advertisements' })).toHaveLength(1);
    const ins = adBox()!.querySelector('ins.adsbygoogle');
    expect(ins).toHaveAttribute('data-ad-client', CLIENT);
    expect(ins).toHaveAttribute('data-ad-slot', SEARCH_UNIT);
    const consentTag = document.getElementById('h2-googlefc');
    const adTag = document.getElementById('h2-adsense');
    expect(consentTag).not.toBeNull();
    expect(
      consentTag!.compareDocumentPosition(adTag!) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(window.adsbygoogle?.pauseAdRequests).toBe(0);
  });

  it('Scenario: the box comes after the list and is not inside the text marked as written by AI', async () => {
    withAdSense();
    renderApp('/search?q=agents');
    await waitFor(() => expect(adBox()).not.toBeNull());
    const list = screen.getByRole('list', { name: 'Search results' });
    expect(list.compareDocumentPosition(adBox()!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(list).queryByRole('complementary')).toBeNull();
    expect(adBox()!.closest('[data-ai-source], [data-ai-generated]')).toBeNull();
  });

  it('Scenario: a search that finds nothing shows no ad and never loads the ad script', async () => {
    withAdSense(() => makeSearch({ results: [], total: 0 }));
    renderApp('/search?q=agents');
    await screen.findByRole('heading', { level: 2, name: /No results|Nothing/ });
    await settle();
    expectNoAd();
  });

  it('Scenario: a search that fails shows no ad and never loads the ad script', async () => {
    withAdSense(() => problem(503, 'Service Unavailable'));
    renderApp('/search?q=agents');
    await screen.findByRole('heading', { level: 2, name: 'Search is unavailable' });
    await settle();
    expectNoAd();
  });

  it('Scenario: new search words ask for a new ad only when their results are there', async () => {
    let held: ((value: unknown) => void) | undefined;
    let calls = 0;
    withAdSense(() => (calls++ === 0 ? makeSearch() : new Promise((resolve) => (held = resolve))));
    const { router } = renderApp('/search?q=agents');
    await waitFor(() => expect(window.adsbygoogle).toHaveLength(1));
    const first = document.querySelector('ins.adsbygoogle');

    await act(() => router.navigate('/search?q=models'));
    await waitFor(() => expect(held).toBeDefined());
    await settle();
    expect(window.adsbygoogle).toHaveLength(1);
    expect(document.querySelector('ins.adsbygoogle')).toBe(first);

    await act(async () => held!(makeSearch()));
    await waitFor(() => expect(window.adsbygoogle).toHaveLength(2));
    expect(document.querySelector('ins.adsbygoogle')).not.toBe(first);
    expect(screen.getAllByRole('complementary', { name: 'Advertisements' })).toHaveLength(1);
  });

  it('Scenario: a filter keeps the ad of the search', async () => {
    withAdSense();
    const { router } = renderApp('/search?q=agents');
    await waitFor(() => expect(window.adsbygoogle).toHaveLength(1));
    const first = document.querySelector('ins.adsbygoogle');

    await act(() => router.navigate('/search?q=agents&type=MODEL'));
    await settle();
    expect(window.adsbygoogle).toHaveLength(1);
    expect(document.querySelector('ins.adsbygoogle')).toBe(first);
  });

  it('Scenario: when a new search fails, the ad leaves with the error and no new ad is asked for', async () => {
    let calls = 0;
    withAdSense(() => (calls++ === 0 ? makeSearch() : problem(503, 'Service Unavailable')));
    const { router } = renderApp('/search?q=agents');
    await waitFor(() => expect(window.adsbygoogle).toHaveLength(1));

    await act(() => router.navigate('/search?q=models'));
    await screen.findByRole('heading', { level: 2, name: 'Search is unavailable' });
    await settle();
    expect(adBox()).toBeNull();
    expect(document.querySelector('ins.adsbygoogle')).toBeNull();
    expect(window.adsbygoogle).toHaveLength(1);
  });

  it('Scenario: a guide question shows no ad and loads nothing from Google', async () => {
    withAdSense();
    renderApp('/search?q=agents&ask=which+agent+framework');
    await screen.findByRole('list', { name: 'Search results' });
    await settle();
    expectNothingFromGoogle();
  });

  it('Scenario: with only the resource ad unit set, a search page shows no ad and asks nothing', async () => {
    withAdSense(undefined, { adsenseSlot: RESOURCE_UNIT, adsenseSlotSearch: null });
    renderApp('/search?q=agents');
    await screen.findByRole('list', { name: 'Search results' });
    await settle();
    expectNothingFromGoogle();
  });
});
