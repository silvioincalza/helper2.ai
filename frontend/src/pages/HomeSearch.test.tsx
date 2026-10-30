import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { json, mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { known, makeCard, makeDetail, makeSearch, makeStatus, unknownDate } from '@/test/fixtures';

const url = '/?q=translate&type=MODEL&license=unknown';

describe('search on the one page', () => {
  it('old /search links land on the one page with the same query and filters, without the old path', async () => {
    const m = mockApi();
    const { router, params } = renderApp('/?q=translate&path=USE_AI&type=MODEL');
    await screen.findByText('Acme Translator');
    expect(router.state.location.pathname).toBe('/');
    expect(params().get('q')).toBe('translate');
    expect(params().getAll('type')).toEqual(['MODEL']);
    expect(new URLSearchParams(m.callsTo('GET', '/search')[0]?.search).get('path')).toBeNull();
  });

  it('sends the url params to the API', async () => {
    const m = mockApi();
    renderApp(url);
    await screen.findByText('Acme Translator');
    const call = m.callsTo('GET', '/search')[0];
    expect(new URLSearchParams(call?.search).getAll('type')).toEqual(['MODEL']);
    expect(new URLSearchParams(call?.search).get('q')).toBe('translate');
  });

  it('shows a loading state with per-source querying', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => { release = r; });
    mockApi({ 'GET /search': async () => { await gate; return makeSearch(); } });
    renderApp(url);
    expect(await screen.findByText('Searching the listed sources…')).toBeInTheDocument();
    expect(await screen.findByText(/GitHub: querying…/)).toBeInTheDocument();
    release();
    await screen.findByText('Acme Translator');
  });

  it('source status panel renders each outcome as text, with served-from, latency and dates', async () => {
    const sources = [
      makeStatus({ sourceId: 'a', name: 'Alpha', outcome: 'SUCCEEDED' }),
      makeStatus({ sourceId: 'b', name: 'Beta', outcome: 'FAILED', servedFrom: 'NONE', latencyMs: null, message: 'Upstream 500' }),
      makeStatus({ sourceId: 'c', name: 'Gamma', outcome: 'TIMED_OUT', servedFrom: 'NONE' }),
      makeStatus({ sourceId: 'd', name: 'Delta', outcome: 'RATE_LIMITED', servedFrom: 'STALE_CACHE', cachedAt: '2026-10-01T00:00:00Z' }),
      makeStatus({ sourceId: 'e', name: 'Epsilon', outcome: 'SKIPPED', servedFrom: 'NONE', mode: 'CURATED' }),
    ];
    mockApi({ 'GET /search': () => makeSearch({ sources, coverage: { partial: true, sourcesConfigured: 5, sourcesQueried: 5, sourcesSucceeded: 1, note: 'n' } }) });
    renderApp(url);
    const toggle = await screen.findByRole('button', { name: /1 of 5 sources responded/ });
    expect(toggle).toHaveTextContent('1 failed');
    expect(toggle).toHaveTextContent('1 timed out');
    expect(toggle).toHaveTextContent('1 rate limited');
    expect(toggle).toHaveTextContent('1 skipped');
    expect(toggle).toHaveTextContent('1 from stale cache');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);
    const table = screen.getByRole('table');
    for (const word of ['Succeeded', 'Failed', 'Timed out', 'Rate limited', 'Skipped', 'Stale cache', 'Curated', 'Live']) {
      expect(within(table).getAllByText(word).length).toBeGreaterThan(0);
    }
    expect(within(table).getByText('Upstream 500')).toBeInTheDocument();
    expect(within(table).getAllByText('120 ms').length).toBeGreaterThan(0);
    expect(within(table).getByText(/Oct 1, 2026/)).toBeInTheDocument(); // cachedAt
  });

  it('the source details toggle controls an element that exists, hidden, while collapsed', async () => {
    mockApi();
    renderApp(url);
    const toggle = await screen.findByRole('button', { name: /sources responded/ });
    const details = document.getElementById(toggle.getAttribute('aria-controls') ?? '');
    expect(details).not.toBeNull();
    expect(details).not.toBeVisible();
    await userEvent.click(toggle);
    expect(details).toBeVisible();
  });

  it('filter checkboxes get valid ids whatever the source text, and repeated notices render once each', async () => {
    const errors = vi.spyOn(console, 'error');
    mockApi({
      'GET /search': () => makeSearch({
        facets: { ...makeSearch().facets, license: [{ value: 'Apache 2.0 / "MIT"', count: 1 }] },
        notices: [{ code: 'CACHE_UNAVAILABLE', message: 'a' }, { code: 'CACHE_UNAVAILABLE', message: 'b' }],
      }),
    });
    renderApp('/?q=translate');
    const box = await within(await screen.findByRole('group', { name: /License/ })).findByRole('checkbox', { name: /Apache 2\.0/ });
    expect(box.id).toMatch(/^[^\s"/]+$/);
    expect(screen.getAllByText('Cache unavailable: every source was queried live for this search.')).toHaveLength(2);
    expect(errors.mock.calls.flat().join(' ')).not.toMatch(/same key/);
  });

  it('shows the partial banner only when coverage.partial', async () => {
    mockApi({ 'GET /search': () => makeSearch({ coverage: { partial: true, sourcesConfigured: 3, sourcesQueried: 3, sourcesSucceeded: 2, note: 'n' } }) });
    renderApp(url);
    expect(await screen.findByText(/Partial results/)).toBeInTheDocument();
    expect(screen.getByText(/Only 2 of 3 queried sources responded/)).toBeInTheDocument();
  });

  it('has no partial banner on full coverage', async () => {
    mockApi();
    renderApp(url);
    await screen.findByText('Acme Translator');
    expect(screen.queryByText(/Partial results/)).not.toBeInTheDocument();
  });

  it('empty state suggests removing filters and lists sources searched', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results: [], total: 0 }) });
    const { params } = renderApp(url);
    expect(await screen.findByText('No results from the sources searched')).toBeInTheDocument();
    expect(screen.getByText('Sources searched:')).toBeInTheDocument();
    // remove filters from the empty state
    await userEvent.click(await screen.findByRole('button', { name: 'Remove all filters' }));
    await waitFor(() => expect(params().getAll('type')).toEqual([]));
  });

  it('all sources failed is NOT shown as an ordinary empty result', async () => {
    let n = 0;
    const failed = [makeStatus({ sourceId: 'a', name: 'Alpha', outcome: 'FAILED', servedFrom: 'NONE' }), makeStatus({ sourceId: 'b', name: 'Beta', outcome: 'TIMED_OUT', servedFrom: 'NONE' })];
    mockApi({
      'GET /search': () => (n++ === 0
        ? makeSearch({ results: [], total: 0, sources: failed, coverage: { partial: true, sourcesConfigured: 2, sourcesQueried: 2, sourcesSucceeded: 0, note: 'n' }, notices: [{ code: 'ALL_SOURCES_FAILED', message: 'All sources failed.' }] })
        : makeSearch()),
    });
    renderApp(url);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No source responded');
    expect(alert).toHaveTextContent('nothing was actually searched');
    expect(within(alert).getByText('Failed')).toBeInTheDocument();
    expect(within(alert).getByText('Timed out')).toBeInTheDocument();
    expect(screen.queryByText('No results from the sources searched')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove all filters' })).not.toBeInTheDocument();
    expect(within(screen.getByRole('main')).queryByRole('button', { name: /Switch to/ })).not.toBeInTheDocument();
    await userEvent.click(within(alert).getByRole('button', { name: /Try again/ }));
    expect(await screen.findByText('Acme Translator')).toBeInTheDocument();
  });

  it('keeps the filter panel populated while a new search loads', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => { release = r; });
    let n = 0;
    mockApi({ 'GET /search': async () => { if (n++ > 0) await gate; return makeSearch(); } });
    renderApp('/?q=translate');
    await screen.findByText('Acme Translator');
    await userEvent.click(within(screen.getByRole('group', { name: /Type/ })).getByRole('checkbox', { name: /Model/ }));
    await screen.findByText('Searching the listed sources…');
    expect(screen.getByRole('group', { name: /License/ })).toBeInTheDocument();
    expect(within(screen.getByRole('group', { name: /Type/ })).getByRole('checkbox', { name: /Product/ })).toBeInTheDocument();
    release();
    await waitFor(() => expect(screen.queryByText('Searching the listed sources…')).not.toBeInTheDocument());
  });

  it('error state offers retry and recovers', async () => {
    let n = 0;
    mockApi({ 'GET /search': () => (n++ === 0 ? problem(503, 'Unavailable') : makeSearch()) });
    renderApp(url);
    expect(await screen.findByRole('alert')).toHaveTextContent('Search is unavailable');
    await userEvent.click(screen.getByRole('button', { name: /Try again/ }));
    expect(await screen.findByText('Acme Translator')).toBeInTheDocument();
  });

  it('error state when the network is down', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('down')));
    renderApp(url);
    expect(await screen.findByRole('alert')).toHaveTextContent('Search is unavailable');
  });

  it('marks stale-cache sources on cards and in the summary', async () => {
    const card = makeCard({ freshness: 'STALE' });
    mockApi({ 'GET /search': () => makeSearch({ results: [card], sources: [makeStatus({ servedFrom: 'STALE_CACHE', cachedAt: '2026-09-01T00:00:00Z' })] }) });
    renderApp(url);
    const article = (await screen.findByText('Acme Translator')).closest('article') as HTMLElement;
    expect(within(article).getAllByText('Stale cache').length).toBeGreaterThan(0);
    expect(within(article).getByText('Stale data')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /from stale cache/ })).toBeInTheDocument();
  });

  it('filters update the URL and the unknown bucket is labelled Unknown', async () => {
    const { params } = (mockApi(), renderApp('/?q=translate'));
    await screen.findByText('Acme Translator');
    const licenseGroup = screen.getByRole('group', { name: /License/ });
    const unknownBox = within(licenseGroup).getByRole('checkbox', { name: /Unknown/ });
    await userEvent.click(unknownBox);
    await waitFor(() => expect(params().getAll('license')).toEqual(['unknown']));
    const tech = screen.getByRole('group', { name: /Technical level/ });
    await userEvent.click(within(tech).getByRole('checkbox', { name: /Unknown/ }));
    await waitFor(() => expect(params().getAll('techLevel')).toEqual(['UNKNOWN']));
    await userEvent.click(within(screen.getByRole('group', { name: /Type/ })).getByRole('checkbox', { name: /Model/ }));
    await waitFor(() => expect(params().getAll('type')).toEqual(['MODEL']));
    expect(params().get('q')).toBe('translate');
  });

  it('active filter chips remove one filter, and clear all', async () => {
    const { params } = (mockApi(), renderApp('/?q=translate&type=MODEL&license=MIT&updatedAfter=2026-01-01'));
    const chips = await screen.findByRole('group', { name: 'Active filters' });
    expect(within(chips).getByText(/Updated after: Jan 1, 2026/)).toBeInTheDocument();
    await userEvent.click(within(chips).getByRole('button', { name: 'Remove filter: Type: Model' }));
    await waitFor(() => expect(params().getAll('type')).toEqual([]));
    expect(params().getAll('license')).toEqual(['MIT']);
    await userEvent.click(screen.getByRole('button', { name: 'Clear all filters' }));
    await waitFor(() => expect(params().getAll('license')).toEqual([]));
    expect(params().get('updatedAfter')).toBeNull();
    expect(params().get('q')).toBe('translate');
  });

  it('updatedAfter date filter writes the URL', async () => {
    const { params } = (mockApi(), renderApp('/?q=translate'));
    await screen.findByText('Acme Translator');
    const input = screen.getByLabelText('Updated after');
    await userEvent.type(input, '2026-05-06');
    await waitFor(() => expect(params().get('updatedAfter')).toBe('2026-05-06'));
  });

  it('timeline view: creation-date order from the API, grouped by month, unknown dates last, never the scan date', async () => {
    const results = [
      makeCard({ id: '11111111-1111-5111-8111-000000000001', title: 'New MCP', timeline: { published: known('2026-10-17T00:00:00Z'), updated: known('2026-10-20T00:00:00Z'), discovered: known('2026-10-26T00:00:00Z', 'helper2') } }),
      makeCard({ id: '11111111-1111-5111-8111-000000000002', title: 'Old library', timeline: { published: known('2023-07-14T00:00:00Z'), updated: known('2026-10-20T00:00:00Z'), discovered: known('2026-10-26T00:00:00Z', 'helper2') } }),
      makeCard({ id: '11111111-1111-5111-8111-000000000003', title: 'Undated tool', timeline: { published: unknownDate, updated: known('2026-10-20T00:00:00Z'), discovered: known('2026-10-26T00:00:00Z', 'helper2') } }),
    ];
    const m = mockApi({ 'GET /search': () => makeSearch({ results, total: 3 }) });
    const { params } = renderApp('/?q=translate&sort=RECENTLY_UPDATED');
    await screen.findByText('New MCP');
    await userEvent.click(screen.getByRole('button', { name: 'Timeline' }));

    await waitFor(() => expect(params().get('view')).toBe('timeline'));
    expect(params().get('sort')).toBeNull();
    const call = new URLSearchParams(m.callsTo('GET', '/search').at(-1)?.search);
    expect(call.get('sort')).toBe('NEWEST');
    expect(call.get('size')).toBe('50');
    expect(call.get('view')).toBeNull();

    const timeline = await screen.findByRole('list', { name: 'Results by creation date' });
    const months = within(timeline).getAllByRole('heading', { level: 3 }).filter((h) => h.classList.contains('tl-month'));
    expect(months.map((h) => h.textContent)).toEqual(['October 2026', 'July 2023', 'Creation date unknown']);
    expect(within(timeline).getByText('Oct 17, 2026')).toBeInTheDocument();
    expect(within(timeline).getByText('Creation date not stated')).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: /Sort by/ })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'List' }));
    await waitFor(() => expect(params().get('view')).toBeNull());
    expect(await screen.findByRole('combobox', { name: /Sort by/ })).toBeInTheDocument();
  });

  it('sort change updates URL and request', async () => {
    const m = mockApi();
    const { params } = renderApp('/?q=translate');
    await screen.findByText('Acme Translator');
    await userEvent.selectOptions(screen.getByLabelText('Sort by'), 'RECENTLY_UPDATED');
    await waitFor(() => expect(params().get('sort')).toBe('RECENTLY_UPDATED'));
    await waitFor(() => expect(m.callsTo('GET', '/search').some((c) => c.search.includes('sort=RECENTLY_UPDATED'))).toBe(true));
  });

  it('keeps the last results on screen, busy and dimmed, while a new search loads; focus stays on the sort select', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => { release = r; });
    let n = 0;
    mockApi({ 'GET /search': async () => { if (n++ > 0) await gate; return makeSearch(); } });
    renderApp('/?q=translate');
    await screen.findByText('Acme Translator');
    const select = screen.getByLabelText('Sort by');
    await userEvent.selectOptions(select, 'NEWEST');
    await screen.findByText('Searching the listed sources…');
    expect(screen.getByText('Acme Translator').closest('[aria-busy]')).toHaveAttribute('aria-busy', 'true');
    expect(document.querySelector('.skeleton-card')).toBeNull();
    expect(select).toHaveFocus();
    release();
    await waitFor(() => expect(screen.queryByText('Searching the listed sources…')).not.toBeInTheDocument());
    expect(screen.getByLabelText('Sort by')).toBe(select);
    expect(select).toHaveFocus();
    expect(screen.getByText('Acme Translator').closest('[aria-busy]')).toHaveAttribute('aria-busy', 'false');
  });

  it('after paging, focus moves to the results heading', async () => {
    mockApi({ 'GET /search': () => makeSearch({ total: 45, size: 20 }) });
    renderApp('/?q=translate');
    await screen.findByText('Page 1 of 3');
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByRole('heading', { name: '45 results' })).toHaveFocus();
  });

  it('paginates', async () => {
    const m = mockApi({ 'GET /search': () => makeSearch({ total: 45, size: 20 }) });
    const { params } = renderApp('/?q=translate');
    await screen.findByText('Page 1 of 3');
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(params().get('page')).toBe('1'));
    await waitFor(() => expect(m.callsTo('GET', '/search').some((c) => c.search.includes('page=1'))).toBe(true));
  });

  it('renders notices, expanded terms and use-case suggestions', async () => {
    mockApi({
      'GET /search': () => makeSearch({
        notices: [{ code: 'CACHE_UNAVAILABLE', message: 'Cache is down; results are live only.' }],
        interpretation: { expandedTerms: ['translation', 'writing'], detectedUseCases: ['translation'] },
      }),
    });
    const { params } = renderApp('/?q=traduzione');
    // Notices are localized by code; the backend message is only a fallback.
    expect(await screen.findByText('Cache unavailable: every source was queried live for this search.')).toBeInTheDocument();
    expect(screen.getByText(/Also searched for: translation, writing/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Translation' }));
    await waitFor(() => expect(params().getAll('useCase')).toEqual(['translation']));
  });

  it('without a query it shows examples and makes no search call at all (the News board reads /recent)', async () => {
    const m = mockApi();
    renderApp('/');
    expect(await screen.findByRole('heading', { name: 'Try an example' })).toBeInTheDocument();
    await screen.findByRole('link', { name: 'Fresh MCP server' });
    expect(m.callsTo('GET', '/search')).toHaveLength(0);
    expect(screen.queryByRole('region', { name: 'Search results' })).not.toBeInTheDocument();
  });

  it('cancels a stale search when params change', async () => {
    const signals: AbortSignal[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const u = new URL(String(input));
      if (u.pathname.endsWith('/search')) {
        if (init?.signal) signals.push(init.signal);
        return new Promise<Response>(() => undefined);
      }
      return json(u.pathname.endsWith('/sources') ? [] : { authenticated: false, roles: [], loginProviders: [] });
    }));
    const { router } = renderApp('/?q=one');
    await waitFor(() => expect(signals).toHaveLength(1));
    await act(() => router.navigate('/?q=two'));
    await waitFor(() => expect(signals[0]?.aborted).toBe(true));
    await settle();
  });

  it('back from a result shows the same results at once and keeps the scroll position', async () => {
    const m = mockApi({ 'GET /resources/11111111-1111-5111-8111-111111111111': () => makeDetail() });
    const { router } = renderApp('/?q=translate');
    const title = await screen.findByRole('link', { name: 'Acme Translator' });
    vi.spyOn(window, 'scrollY', 'get').mockReturnValue(840);
    await userEvent.click(title);
    await screen.findByRole('heading', { level: 1, name: 'Acme Translator' });
    expect(window.scrollTo).toHaveBeenLastCalledWith(0, 0);

    await act(() => router.navigate(-1));
    expect(screen.getByRole('link', { name: 'Acme Translator' })).toBeInTheDocument();
    expect(screen.queryByText('Searching the listed sources…')).not.toBeInTheDocument();
    expect(window.scrollTo).toHaveBeenLastCalledWith(0, 840);
    expect(m.callsTo('GET', '/search')).toHaveLength(1);
  });
});
