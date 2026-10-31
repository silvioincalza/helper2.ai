import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { json, mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { known, makeCard, makeDetail, makeSearch, makeStatus, unknownDate } from '@/test/fixtures';

const A = '11111111-1111-5111-8111-000000000001';
const B = '11111111-1111-5111-8111-000000000002';
const C = '11111111-1111-5111-8111-000000000003';
const three = () => makeSearch({
  results: [
    makeCard({ id: A, title: 'Alpha MCP', type: 'MCP_SERVER', description: 'Alpha gives agents a database.' }),
    makeCard({ id: B, title: 'Beta library', type: 'LIBRARY', description: 'Beta parses documents.', license: null }),
    makeCard({ id: C, title: 'Gamma model', type: 'MODEL', description: 'Gamma embeds text.' }),
  ],
  total: 3,
});
/** A result row: a button named by the title first, then type, site and pick. */
const row = (title: string) => screen.getByRole('button', { name: new RegExp(`^${title} `) });
const preview = () => screen.getByRole('region', { name: /^(Alpha MCP|Beta library|Gamma model|Acme Translator)$/ });
/** Up to bp-split (860 px) the page has no preview panel. */
const narrowScreen = () => vi.stubGlobal('matchMedia', (q: string) => ({ matches: q.includes('max-width: 860px'), media: q, addEventListener: () => undefined, removeEventListener: () => undefined }));

describe('the results page: where it lives', () => {
  it('given a search on /search, it shows the results and names /search canonical', async () => {
    mockApi();
    renderApp('/search?q=translate&type=MODEL');
    expect(await screen.findByRole('heading', { level: 1, name: 'Results for “translate”' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /^Acme Translator / })).toBeInTheDocument();
    expect(document.head.querySelector('link[rel="canonical"]')).toHaveAttribute('href', 'http://localhost:3000/search?q=translate&type=MODEL');
  });

  it('given /?q=…, it shows the same page, the box in the header, and /search as the canonical URL', async () => {
    mockApi();
    const { router } = renderApp('/?q=translate&ask=translate+documents+for+free');
    expect(await screen.findByRole('button', { name: /^Acme Translator / })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
    expect(screen.queryByRole('region', { name: 'News' })).not.toBeInTheDocument();
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe('http://localhost:3000/search?q=translate');
    await settle();
  });

  it('given /search with nothing to search, it goes to the home page', async () => {
    mockApi();
    const { router } = renderApp('/search?type=MODEL');
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(await screen.findByRole('region', { name: 'News' })).toBeInTheDocument();
  });

  it('sends the URL filters to the API and drops parameters it does not know', async () => {
    const m = mockApi();
    renderApp('/search?q=translate&path=USE_AI&type=MODEL&license=unknown');
    await screen.findByRole('button', { name: /^Acme Translator / });
    const call = new URLSearchParams(m.callsTo('GET', '/search')[0]?.search);
    expect(call.get('q')).toBe('translate');
    expect(call.getAll('type')).toEqual(['MODEL']);
    expect(call.get('path')).toBeNull();
  });
});

describe('the result list and the preview (from 861 px)', () => {
  it('given results, the first row is selected and the preview shows it: why, facts in Use and Build, listed by, dates', async () => {
    mockApi({ 'GET /search': three });
    const { container } = renderApp('/search?q=agents');
    const alpha = await screen.findByRole('button', { name: /^Alpha MCP / });
    expect(alpha).toHaveAttribute('aria-current', 'true');
    const panel = preview();
    expect(within(panel).getByRole('heading', { level: 2, name: 'Alpha MCP' })).toBeInTheDocument();
    expect(within(panel).getByText('Alpha gives agents a database.')).toBeInTheDocument();
    for (const label of ['Description', 'Facts', 'Listed by', 'Timeline']) expect(within(panel).getByRole('heading', { level: 3, name: label })).toBeInTheDocument();
    expect(within(panel).getByText('Use')).toBeInTheDocument();
    expect(within(panel).getByText('Build')).toBeInTheDocument();
    expect(within(panel).getByRole('link', { name: /Open on source/ })).toHaveAttribute('href', 'https://www.acme.example/translator');
    expect(within(panel).getByRole('link', { name: /Full details/ })).toHaveAttribute('href', `/resource/${A}`);
    expect(within(panel).getByRole('link', { name: /GitHub/ })).toHaveAttribute('href', 'https://github.com/acme/translator');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('when the user clicks a row, the preview shows it and the URL keeps it without a new history entry', async () => {
    const m = mockApi({ 'GET /search': three });
    const { router, params } = renderApp('/search?q=agents');
    await userEvent.click(await screen.findByRole('button', { name: /^Beta library / }));
    expect(params().get('id')).toBe(B);
    expect(router.state.historyAction).toBe('REPLACE');
    expect(row('Beta library')).toHaveAttribute('aria-current', 'true');
    expect(row('Alpha MCP')).not.toHaveAttribute('aria-current');
    // A value no source stated reads Unknown, in the not-stated style.
    expect(within(preview()).getByText('License').closest('div')?.querySelector('dd')).toHaveClass('not-stated');
    expect(m.callsTo('GET', '/search')).toHaveLength(1);
  });

  it('given a shared link with an id, it opens that result', async () => {
    mockApi({ 'GET /search': three });
    renderApp(`/search?q=agents&id=${C}`);
    await screen.findByRole('button', { name: /^Gamma model / });
    expect(row('Gamma model')).toHaveAttribute('aria-current', 'true');
    expect(within(preview()).getByRole('heading', { level: 2, name: 'Gamma model' })).toBeInTheDocument();
  });

  it('given an id that is not among the results, it selects the first row and leaves the id alone', async () => {
    mockApi({ 'GET /search': three });
    const { params } = renderApp('/search?q=agents&id=%3Cscript%3E');
    await screen.findByRole('button', { name: /^Alpha MCP / });
    expect(row('Alpha MCP')).toHaveAttribute('aria-current', 'true');
    expect(params().get('id')).toBe('<script>');
    expect(document.querySelector('script')).toBeNull();
  });

  it('with the keyboard: one row in the tab order, arrows move and select, Home and End jump, Enter selects', async () => {
    mockApi({ 'GET /search': three });
    const { params } = renderApp('/search?q=agents');
    const alpha = await screen.findByRole('button', { name: /^Alpha MCP / });
    expect(alpha).toHaveAttribute('tabindex', '0');
    expect(row('Beta library')).toHaveAttribute('tabindex', '-1');
    act(() => alpha.focus());
    await userEvent.keyboard('{ArrowDown}');
    expect(row('Beta library')).toHaveFocus();
    expect(row('Beta library')).toHaveAttribute('aria-current', 'true');
    expect(params().get('id')).toBe(B);
    await userEvent.keyboard('{End}');
    expect(row('Gamma model')).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(row('Gamma model')).toHaveFocus();
    await userEvent.keyboard('{Home}');
    expect(row('Alpha MCP')).toHaveFocus();
    await userEvent.keyboard('{ArrowUp}');
    expect(row('Alpha MCP')).toHaveFocus();
    act(() => row('Gamma model').focus());
    await userEvent.keyboard('{Enter}');
    expect(params().get('id')).toBe(C);
    expect(within(preview()).getByRole('heading', { level: 2, name: 'Gamma model' })).toBeInTheDocument();
  });

  it('Back leaves the search instead of stepping through the rows', async () => {
    mockApi({ 'GET /search': three });
    const { router } = renderApp('/mission');
    await act(() => router.navigate('/search?q=agents'));
    await userEvent.click(await screen.findByRole('button', { name: /^Beta library / }));
    await userEvent.click(row('Gamma model'));
    await act(() => router.navigate(-1));
    expect(router.state.location.pathname).toBe('/mission');
  });

  it('marks a source served from a stale cache where the preview lists it, and in the sources', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results: [makeCard({ freshness: 'STALE' })], sources: [makeStatus({ servedFrom: 'STALE_CACHE', cachedAt: '2026-09-01T00:00:00Z' })] }) });
    renderApp('/search?q=translate');
    await screen.findByRole('button', { name: /^Acme Translator / });
    expect(within(preview()).getByText('Stale cache')).toBeInTheDocument();
    expect(within(preview()).getByText('Stale data')).toBeInTheDocument();
    expect(screen.getByText(/1 from stale cache/)).toBeInTheDocument();
  });
});

describe('the result list up to 860 px', () => {
  beforeEach(narrowScreen);

  it('opens the selected result under its row, with three facts and a link to its page; no preview panel', async () => {
    mockApi({ 'GET /search': three });
    const { container } = renderApp('/search?q=agents');
    await screen.findByRole('button', { name: /^Alpha MCP / });
    expect(screen.queryByRole('region', { name: 'Alpha MCP' })).not.toBeInTheDocument();
    const inline = screen.getByRole('group', { name: 'Preview of Alpha MCP' });
    expect(inline.closest('li')).toContainElement(row('Alpha MCP'));
    expect(within(inline).getAllByRole('definition')).toHaveLength(3);
    expect(within(inline).getByRole('link', { name: /Full details/ })).toHaveAttribute('href', `/resource/${A}`);

    await userEvent.click(row('Gamma model'));
    expect(screen.queryByRole('group', { name: 'Preview of Alpha MCP' })).not.toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Preview of Gamma model' }).closest('li')).toContainElement(row('Gamma model'));
    expect(await axeViolations(container)).toEqual([]);
  });
});

describe('the sources under the results', () => {
  it('sums up the fan-out and gives one tile per source with its time, cache age or what went wrong', async () => {
    const sources = [
      makeStatus({ sourceId: 'a', name: 'Alpha', outcome: 'SUCCEEDED', resultCount: 4, latencyMs: 320 }),
      makeStatus({ sourceId: 'b', name: 'Beta', outcome: 'FAILED', servedFrom: 'NONE', latencyMs: null, resultCount: 0 }),
      makeStatus({ sourceId: 'c', name: 'Gamma', outcome: 'TIMED_OUT', servedFrom: 'NONE', resultCount: 0 }),
      makeStatus({ sourceId: 'd', name: 'Delta', outcome: 'RATE_LIMITED', servedFrom: 'STALE_CACHE', cachedAt: '2026-10-22T08:00:00Z', resultCount: 3 }),
      makeStatus({ sourceId: 'e', name: 'Epsilon', outcome: 'SKIPPED', servedFrom: 'NONE', mode: 'CURATED', resultCount: 0 }),
    ];
    mockApi({ 'GET /search': () => makeSearch({ sources, total: 6, coverage: { partial: true, sourcesConfigured: 5, sourcesQueried: 5, sourcesSucceeded: 1, note: 'Coverage is limited.' } }) });
    renderApp('/search?q=translate');
    const summary = await screen.findByRole('region', { name: 'Sources' });
    expect(within(summary).getByText('fan-out · 5 sources · 7 hits → 6 after merge')).toBeInTheDocument();
    const line = within(summary).getByText(/1 of 5 sources responded/);
    for (const part of ['1 failed', '1 timed out', '1 rate limited', '1 skipped', '1 from stale cache']) expect(line).toHaveTextContent(part);
    const tiles = within(within(summary).getByRole('list', { name: 'Sources searched' })).getAllByRole('listitem');
    expect(tiles.map((t) => t.textContent)).toEqual(['Alpha320 ms', 'BetaFailed', 'GammaTimed out', 'DeltaRate limited', 'EpsilonSkipped']);
    expect(within(summary).getByText('Coverage is limited.')).toBeInTheDocument();
  });

  it('says in one quiet line how many web searches are left today for search', async () => {
    mockApi({ 'GET /search': () => makeSearch({ webSearches: { limit: 3, remaining: 2 } }) });
    renderApp('/search?q=translate');
    expect(await screen.findByText('Web searches today: 2 of 3 left for search')).toBeInTheDocument();
  });

  it('at the daily limit, says stored web results still answer and when new ones come back, with no offer to buy more', async () => {
    mockApi({ 'GET /search': () => makeSearch({ webSearches: { limit: 3, remaining: 0 } }) });
    renderApp('/search?q=translate');
    const line = await screen.findByText(/Web searches today/);
    expect(line).toHaveTextContent('daily limit reached for search; stored web results still answer, new ones after 00:00 UTC');
    expect(within(line).queryByRole('link')).toBeNull();
  });

  it('says nothing about web searches when no web source is metered', async () => {
    mockApi({ 'GET /search': () => makeSearch({ webSearches: null }) });
    renderApp('/search?q=translate');
    await screen.findByRole('region', { name: 'Sources' });
    expect(screen.queryByText(/Web searches today/)).not.toBeInTheDocument();
  });
});

describe('the states of the results page', () => {
  it('while the first answer loads: skeleton rows, the sources being asked, and a status', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => { release = r; });
    mockApi({ 'GET /search': async () => { await gate; return makeSearch(); } });
    renderApp('/search?q=translate');
    expect(await screen.findByRole('status')).toHaveTextContent('Searching the listed sources…');
    expect(await screen.findByText(/GitHub: querying…/)).toBeInTheDocument();
    expect(document.querySelectorAll('.result-skeleton')).toHaveLength(5);
    release();
    await screen.findByRole('button', { name: /^Acme Translator / });
    expect(document.querySelector('.result-skeleton')).toBeNull();
  });

  it('while a new search loads, the last results stay, busy and dimmed, and focus stays on the sort', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => { release = r; });
    let n = 0;
    mockApi({ 'GET /search': async () => { if (n++ > 0) await gate; return makeSearch(); } });
    renderApp('/search?q=translate');
    await screen.findByRole('button', { name: /^Acme Translator / });
    const select = screen.getByLabelText('Sort by');
    await userEvent.selectOptions(select, 'NEWEST');
    await screen.findByText('Searching the listed sources…');
    expect(row('Acme Translator').closest('[aria-busy]')).toHaveAttribute('aria-busy', 'true');
    expect(document.querySelector('.result-skeleton')).toBeNull();
    expect(select).toHaveFocus();
    release();
    await waitFor(() => expect(screen.queryByText('Searching the listed sources…')).not.toBeInTheDocument());
    expect(select).toHaveFocus();
  });

  it('when some sources failed: says so above the list, with the way to the sources and Try again', async () => {
    let n = 0;
    const m = mockApi({ 'GET /search': () => makeSearch({ coverage: { partial: n++ === 0, sourcesConfigured: 3, sourcesQueried: 3, sourcesSucceeded: 2, note: 'n' } }) });
    renderApp('/search?q=translate');
    const banner = (await screen.findByText(/Partial results/)).closest('.banner') as HTMLElement;
    expect(banner).toHaveTextContent('Only 2 of 3 queried sources responded');
    expect(within(banner).getByRole('link', { name: 'See which sources' })).toHaveAttribute('href', '#sources-h');
    await userEvent.click(within(banner).getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(m.callsTo('GET', '/search')).toHaveLength(2));
    await waitFor(() => expect(screen.queryByText(/Partial results/)).not.toBeInTheDocument());
  });

  it('when every source failed: not shown as "no results", with the sources and Try again', async () => {
    let n = 0;
    const failed = [makeStatus({ sourceId: 'a', name: 'Alpha', outcome: 'FAILED', servedFrom: 'NONE' }), makeStatus({ sourceId: 'b', name: 'Beta', outcome: 'TIMED_OUT', servedFrom: 'NONE' })];
    mockApi({
      'GET /search': () => (n++ === 0
        ? makeSearch({ results: [], total: 0, sources: failed, coverage: { partial: true, sourcesConfigured: 2, sourcesQueried: 2, sourcesSucceeded: 0, note: 'n' }, notices: [{ code: 'ALL_SOURCES_FAILED', message: 'All sources failed.' }] })
        : makeSearch()),
    });
    const { container } = renderApp('/search?q=translate&type=MODEL');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No source responded');
    expect(alert).toHaveTextContent('nothing was actually searched');
    expect(within(alert).getByText('Failed')).toBeInTheDocument();
    expect(screen.queryByText(/Partial results/)).not.toBeInTheDocument();
    expect(screen.queryByText('No results from the sources searched')).not.toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(within(alert).getByRole('button', { name: /Try again/ }));
    expect(await screen.findByRole('button', { name: /^Acme Translator / })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '1 result' })).toHaveFocus();
  });

  it('when nothing matched: next steps (remove filters, fewer words, ask the guide) and the sources searched', async () => {
    const m = mockApi({ 'GET /search': () => makeSearch({ results: [], total: 0 }), 'POST /guide/stream': () => new Promise(() => undefined) });
    const { params, container } = renderApp('/search?q=translate&type=MODEL');
    expect(await screen.findByText('No results from the sources searched')).toBeInTheDocument();
    expect(screen.getByText('Sources searched:')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Ask the guide in plain words' }));
    await waitFor(() => expect(params().get('ask')).toBe('translate'));
    await waitFor(() => expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1));
    await userEvent.click(screen.getByRole('button', { name: 'Remove all filters' }));
    await waitFor(() => expect(params().getAll('type')).toEqual([]));
  });

  it('when search is unavailable: says so and Try again recovers', async () => {
    let n = 0;
    mockApi({ 'GET /search': () => (n++ === 0 ? problem(503, 'Unavailable') : makeSearch()) });
    renderApp('/search?q=translate');
    expect(await screen.findByRole('alert')).toHaveTextContent('Search is unavailable');
    await userEvent.click(screen.getByRole('button', { name: /Try again/ }));
    expect(await screen.findByRole('button', { name: /^Acme Translator / })).toBeInTheDocument();
    // The button went away with the error: focus is on the results heading, not on the page body.
    expect(screen.getByRole('heading', { name: '1 result' })).toHaveFocus();
  });

  it('when search is still unavailable after Try again: focus stays on the results heading', async () => {
    mockApi({ 'GET /search': () => problem(503, 'Unavailable') });
    renderApp('/search?q=translate');
    await userEvent.click(within(await screen.findByRole('alert')).getByRole('button', { name: /Try again/ }));
    await waitFor(() => expect(screen.getByRole('heading', { name: /^Search results$/ })).toHaveFocus());
    expect(screen.getByRole('alert')).toHaveTextContent('Search is unavailable');
  });

  it('when the network is down: says search is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('down')));
    renderApp('/search?q=translate');
    expect(await screen.findByRole('alert')).toHaveTextContent('Search is unavailable');
  });
});

describe('narrowing, sorting and paging the results', () => {
  it('filters write the URL; the unknown bucket is labelled Unknown and spelled per the API', async () => {
    const { params } = (mockApi(), renderApp('/search?q=translate'));
    await screen.findByRole('button', { name: /^Acme Translator / });
    await userEvent.click(screen.getByRole('button', { name: 'Filters' }));
    await userEvent.click(within(screen.getByRole('group', { name: /License/ })).getByRole('checkbox', { name: /Unknown/ }));
    await waitFor(() => expect(params().getAll('license')).toEqual(['unknown']));
    await userEvent.click(within(screen.getByRole('group', { name: /Technical level/ })).getByRole('checkbox', { name: /Unknown/ }));
    await waitFor(() => expect(params().getAll('techLevel')).toEqual(['UNKNOWN']));
    expect(params().get('q')).toBe('translate');
  });

  it('filter checkboxes get valid ids whatever the source text, and repeated notices render once each', async () => {
    const errors = vi.spyOn(console, 'error');
    mockApi({
      'GET /search': () => makeSearch({
        facets: { ...makeSearch().facets, license: [{ value: 'Apache 2.0 / "MIT"', count: 1 }] },
        notices: [{ code: 'CACHE_UNAVAILABLE', message: 'a' }, { code: 'CACHE_UNAVAILABLE', message: 'b' }],
      }),
    });
    renderApp('/search?q=translate');
    const box = await within(await screen.findByRole('group', { name: /License/ })).findByRole('checkbox', { name: /Apache 2\.0/ });
    expect(box.id).toMatch(/^[^\s"/]+$/);
    expect(screen.getAllByText('Cache unavailable: every source was queried live for this search.')).toHaveLength(2);
    expect(errors.mock.calls.flat().join(' ')).not.toMatch(/same key/);
  });

  it('keeps the filters while a new search loads', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => { release = r; });
    let n = 0;
    mockApi({ 'GET /search': async () => { if (n++ > 0) await gate; return makeSearch(); } });
    renderApp('/search?q=translate');
    await screen.findByRole('button', { name: /^Acme Translator / });
    await userEvent.click(within(screen.getByRole('group', { name: /Type/ })).getByRole('checkbox', { name: /Model/ }));
    await screen.findByText('Searching the listed sources…');
    expect(within(screen.getByRole('group', { name: /Type/ })).getByRole('checkbox', { name: /Product/ })).toBeInTheDocument();
    release();
    await waitFor(() => expect(screen.queryByText('Searching the listed sources…')).not.toBeInTheDocument());
  });

  it('active filter chips remove one filter, and clear all', async () => {
    const { params } = (mockApi(), renderApp('/search?q=translate&type=MODEL&license=MIT&updatedAfter=2026-01-01'));
    const chips = await screen.findByRole('group', { name: 'Active filters' });
    expect(within(chips).getByText(/Updated after: Jan 1, 2026/)).toBeInTheDocument();
    await userEvent.click(within(chips).getByRole('button', { name: 'Remove filter: Type: Model' }));
    await waitFor(() => expect(params().getAll('type')).toEqual([]));
    expect(params().getAll('license')).toEqual(['MIT']);
    await userEvent.click(screen.getByRole('button', { name: 'Clear all filters' }));
    await waitFor(() => expect(params().getAll('license')).toEqual([]));
    expect(params().get('updatedAfter')).toBeNull();
  });

  it('the updated-after date writes the URL', async () => {
    const { params } = (mockApi(), renderApp('/search?q=translate'));
    await screen.findByRole('button', { name: /^Acme Translator / });
    await userEvent.type(screen.getByLabelText('Updated after'), '2026-05-06');
    await waitFor(() => expect(params().get('updatedAfter')).toBe('2026-05-06'));
  });

  it('notices, words added from the word list and use-case hints show above the list', async () => {
    mockApi({
      'GET /search': () => makeSearch({
        notices: [{ code: 'CACHE_UNAVAILABLE', message: 'Cache is down; results are live only.' }],
        interpretation: { expandedTerms: ['translation', 'writing'], detectedUseCases: ['translation'] },
      }),
    });
    const { params } = renderApp('/search?q=traduzione');
    expect(await screen.findByText('Cache unavailable: every source was queried live for this search.')).toBeInTheDocument();
    expect(screen.getByText(/Also searched for: translation, writing/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Translation' }));
    await waitFor(() => expect(params().getAll('useCase')).toEqual(['translation']));
  });

  it('the sort writes the URL and the request', async () => {
    const m = mockApi();
    const { params } = renderApp('/search?q=translate');
    await screen.findByRole('button', { name: /^Acme Translator / });
    await userEvent.selectOptions(screen.getByLabelText('Sort by'), 'RECENTLY_UPDATED');
    await waitFor(() => expect(params().get('sort')).toBe('RECENTLY_UPDATED'));
    await waitFor(() => expect(m.callsTo('GET', '/search').some((c) => c.search.includes('sort=RECENTLY_UPDATED'))).toBe(true));
  });

  it('the timeline groups the same rows by creation month, unknown dates last, never the scan date', async () => {
    const results = [
      makeCard({ id: A, title: 'New MCP', timeline: { published: known('2026-10-17T00:00:00Z'), updated: known('2026-10-20T00:00:00Z'), discovered: known('2026-10-26T00:00:00Z', 'helper2') } }),
      makeCard({ id: B, title: 'Old library', timeline: { published: known('2023-07-14T00:00:00Z'), updated: known('2026-10-20T00:00:00Z'), discovered: known('2026-10-26T00:00:00Z', 'helper2') } }),
      makeCard({ id: C, title: 'Undated tool', timeline: { published: unknownDate, updated: known('2026-10-20T00:00:00Z'), discovered: known('2026-10-26T00:00:00Z', 'helper2') } }),
    ];
    const m = mockApi({ 'GET /search': () => makeSearch({ results, total: 3 }) });
    const { params } = renderApp('/search?q=translate&sort=RECENTLY_UPDATED');
    await screen.findByRole('button', { name: /^New MCP / });
    await userEvent.click(screen.getByRole('button', { name: 'Timeline' }));

    await waitFor(() => expect(params().get('view')).toBe('timeline'));
    expect(params().get('sort')).toBeNull();
    const call = new URLSearchParams(m.callsTo('GET', '/search').at(-1)?.search);
    expect(call.get('sort')).toBe('NEWEST');
    expect(call.get('size')).toBe('50');

    const timeline = await screen.findByRole('list', { name: 'Results by creation date' });
    expect(within(timeline).getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['October 2026', 'July 2023', 'Creation date unknown']);
    expect(within(timeline).getByText('Oct 17, 2026')).toBeInTheDocument();
    expect(within(timeline).getByText('Creation date not stated')).toBeInTheDocument();
    // The same rows: arrows move across the months.
    act(() => row('New MCP').focus());
    await userEvent.keyboard('{End}');
    expect(row('Undated tool')).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'List' }));
    await waitFor(() => expect(params().get('view')).toBeNull());
    expect(await screen.findByRole('combobox', { name: /Sort by/ })).toBeInTheDocument();
  });

  it('pages, and after paging focus moves to the results heading', async () => {
    const m = mockApi({ 'GET /search': () => makeSearch({ total: 45, size: 20 }) });
    const { params } = renderApp('/search?q=translate');
    await screen.findByText('Page 1 of 3');
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByRole('heading', { name: '45 results' })).toHaveFocus();
    await waitFor(() => expect(params().get('page')).toBe('1'));
    await waitFor(() => expect(m.callsTo('GET', '/search').some((c) => c.search.includes('page=1'))).toBe(true));
  });
});

describe('moving around', () => {
  it('a new search aborts the one still running', async () => {
    const signals: AbortSignal[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const u = new URL(String(input));
      if (u.pathname.endsWith('/search')) {
        if (init?.signal) signals.push(init.signal);
        return new Promise<Response>(() => undefined);
      }
      return json(u.pathname.endsWith('/sources') ? [] : { authenticated: false, roles: [], loginProviders: [] });
    }));
    const { router } = renderApp('/search?q=one');
    await waitFor(() => expect(signals).toHaveLength(1));
    await act(() => router.navigate('/search?q=two'));
    await waitFor(() => expect(signals[0]?.aborted).toBe(true));
    await settle();
  });

  it('back from a resource page shows the same results and selection at once and keeps the scroll position', async () => {
    const m = mockApi({ 'GET /search': three, [`GET /resources/${B}`]: () => makeDetail({ card: makeCard({ id: B, title: 'Beta library' }) }) });
    const { router } = renderApp('/search?q=agents');
    await userEvent.click(await screen.findByRole('button', { name: /^Beta library / }));
    vi.spyOn(window, 'scrollY', 'get').mockReturnValue(840);
    await userEvent.click(within(preview()).getByRole('link', { name: /Full details/ }));
    await screen.findByRole('heading', { level: 1, name: 'Beta library' });
    expect(window.scrollTo).toHaveBeenLastCalledWith(0, 0);

    await act(() => router.navigate(-1));
    expect(row('Beta library')).toHaveAttribute('aria-current', 'true');
    expect(screen.queryByText('Searching the listed sources…')).not.toBeInTheDocument();
    expect(window.scrollTo).toHaveBeenLastCalledWith(0, 840);
    expect(m.callsTo('GET', '/search')).toHaveLength(1);
  });
});
