import { screen, waitFor, within } from '@testing-library/react';
import type { GuideResponse } from '@/api/types';
import { heldSse, mockApi, problem, sse } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { makeDetail, makeSearch } from '@/test/fixtures';

const ID = '11111111-1111-5111-8111-111111111111';
const RESOURCE = `/resource/${ID}/acme-translator`;
const CLIENT = 'ca-pub-4688630163076563';
const RESOURCE_UNIT = '1234567890';
const SEARCH_UNIT = '2345678901';
const GUIDE_UNIT = '3456789012';
type Units = {
  adsenseSlot: string | null;
  adsenseSlotSearch: string | null;
  adsenseSlotGuide: string | null;
};
const site = (units: Units) => () => ({
  monetisation: {
    githubSponsorsUrl: null,
    stripePaymentUrl: null,
    ga4MeasurementId: null,
    adsenseClient: CLIENT,
    ...units,
  },
});
const BOTH: Units = {
  adsenseSlot: RESOURCE_UNIT,
  adsenseSlotSearch: SEARCH_UNIT,
  adsenseSlotGuide: null,
};
const ALL: Units = { ...BOTH, adsenseSlotGuide: GUIDE_UNIT };
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
    withAdSense(undefined, { ...BOTH, adsenseSlot: null });
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

  it('Scenario: a guide question shows no ad and loads nothing from Google while the guide ad unit is not set', async () => {
    withAdSense();
    renderApp('/search?q=agents&ask=which+agent+framework');
    await screen.findByRole('list', { name: 'Search results' });
    await settle();
    expectNothingFromGoogle();
  });

  it('Scenario: with only the resource ad unit set, a search page shows no ad and asks nothing', async () => {
    withAdSense(undefined, { ...BOTH, adsenseSlotSearch: null });
    renderApp('/search?q=agents');
    await screen.findByRole('list', { name: 'Search results' });
    await settle();
    expectNothingFromGoogle();
  });
});

describe('Feature: the AdSense slot below a guide answer (ADR 0042)', () => {
  const ASK = '/search?q=postgres+mcp&ask=which+mcp+server+for+postgres';
  const NO_PICK: GuideResponse = {
    status: 'OK',
    searchQuery: 'postgres mcp',
    recommendations: [],
    candidatesConsidered: 0,
    sources: [],
    notices: [],
    generatedAt: '2026-10-30T10:00:00Z',
  };
  const PICKED: GuideResponse = {
    ...NO_PICK,
    candidatesConsidered: 1,
    recommendations: [
      {
        resourceId: 'bbbbbbbb-bbbb-5bbb-8bbb-bbbbbbbbbbbb',
        title: 'Postgres MCP',
        url: 'https://github.com/acme/postgres-mcp',
        type: 'MCP_SERVER',
        reason: 'Read-only access fits an agent that only reads.',
        quote: 'read-only access',
        sourceIds: ['github'],
        aiReasoned: true,
        reasonCheck: 'NOT_CHECKED',
      },
    ],
  };
  const withGuide = (guide: () => unknown, units: Units = ALL) =>
    mockApi({
      'GET /site': site(units),
      'GET /search': () => makeSearch(),
      'POST /guide/stream': guide,
    });
  const picksBar = () => screen.getByRole('region', { name: 'Guide picks' });

  it('Scenario: a finished answer with a pick shows one labelled box with the guide ad unit, and no search box', async () => {
    withGuide(() => sse(PICKED));
    renderApp(ASK);
    await waitFor(() => expect(adBox()).not.toBeNull());
    expect(screen.getAllByRole('complementary', { name: 'Advertisements' })).toHaveLength(1);
    expect(adBox()!.querySelector('ins.adsbygoogle')).toHaveAttribute('data-ad-slot', GUIDE_UNIT);
    expect(document.getElementById('h2-googlefc')).not.toBeNull();
    expect(window.adsbygoogle).toHaveLength(1);
  });

  it('Scenario: the box comes after the picks and is not inside text marked as written by AI', async () => {
    withGuide(() => sse(PICKED));
    renderApp(ASK);
    await waitFor(() => expect(adBox()).not.toBeNull());
    expect(
      picksBar().compareDocumentPosition(adBox()!) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(within(picksBar()).queryByRole('complementary')).toBeNull();
    expect(adBox()!.closest('[data-ai-generated], [data-ai-provider]')).toBeNull();
    expect(adBox()!.querySelector('[data-ai-generated]')).toBeNull();
  });

  it('Scenario: while the guide is still reading there is no ad, and it comes with the answer', async () => {
    const stream = heldSse();
    withGuide(() => stream.response());
    renderApp(ASK);
    await waitFor(() => expect(picksBar()).toHaveTextContent('Guide is reading'));
    await settle();
    expectNoAd();

    stream.finish(PICKED);
    await waitFor(() => expect(adBox()).not.toBeNull());
  });

  it('Scenario: an answer without a pick shows no ad', async () => {
    withGuide(() => sse(NO_PICK));
    renderApp(ASK);
    await waitFor(() => expect(picksBar()).not.toHaveTextContent('Guide is reading'));
    await settle();
    expectNoAd();
  });

  it('Scenario: a guide that fails shows no ad', async () => {
    withGuide(() => problem(503, 'Service Unavailable'));
    renderApp(ASK);
    await within(await screen.findByRole('list', { name: 'Search results' })).findAllByRole(
      'listitem',
    );
    await settle();
    expectNoAd();
  });
});
