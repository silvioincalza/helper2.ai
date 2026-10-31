import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { makeSearch, makeStatus } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';


const url = '/search?q=translate';
const failed = makeStatus({ sourceId: 'b', name: 'Beta', outcome: 'FAILED', servedFrom: 'NONE', latencyMs: null });

describe('states: every state says what happened and offers a next step', () => {
  it('loading: a status with skeleton rows shaped like the list', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => { release = r; });
    mockApi({ 'GET /search': async () => { await gate; return makeSearch(); } });
    renderApp(url);
    const status = (await screen.findByText('Searching the listed sources…')).closest('[role="status"]');
    expect(status).not.toBeNull();
    expect(document.querySelectorAll('.h2-skeleton__row')).toHaveLength(4);
    release();
    expect((await screen.findAllByText('Acme Translator')).length).toBeGreaterThan(0);
    expect(document.querySelector('.h2-skeleton')).toBeNull();
  });

  it('guide answering: the list is already there while the picks bar reads', async () => {
    mockApi({ 'GET /search': () => makeSearch(), 'POST /guide/stream': () => new Promise<Response>(() => undefined) });
    renderApp(`${url}&ask=translate+documents+for+free`);
    expect((await screen.findAllByText('Acme Translator')).length).toBeGreaterThan(0);
    expect(screen.getByText('Guide is reading the results… picks will appear here')).toBeInTheDocument();
  });

  it('no results: asks the guide or opens the sources', async () => {
    let n = 0;
    mockApi({
      'GET /search': () => makeSearch({ results: [], total: 0 }),
      'POST /guide/stream': () => { n++; return new Promise<Response>(() => undefined); },
    });
    const { params } = renderApp(url);
    const empty = (await screen.findByText('No results from the sources searched')).closest('[role="status"]') as HTMLElement;
    expect(empty).toHaveTextContent('Try different or fewer words.');
    expect(within(empty).getByRole('link', { name: 'See the sources we search' })).toHaveAttribute('href', '/sources');
    await userEvent.click(within(empty).getByRole('button', { name: 'Ask the guide instead' }));
    await waitFor(() => expect(params().get('ask')).toBe('translate'));
    await waitFor(() => expect(n).toBe(1));
  });

  it('no results for a search too short to ask: no guide button that would do nothing', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results: [], total: 0 }) });
    renderApp('/search?q=ai');
    const empty = (await screen.findByText('No results from the sources searched')).closest('[role="status"]') as HTMLElement;
    expect(within(empty).queryByRole('button', { name: 'Ask the guide instead' })).not.toBeInTheDocument();
    expect(within(empty).getByRole('link', { name: 'See the sources we search' })).toBeInTheDocument();
  });

  it('some sources failed: results stay, the line says so, links to the tiles and retries', async () => {
    let n = 0;
    mockApi({
      'GET /search': () => (n++ === 0
        ? makeSearch({ sources: [makeStatus(), failed], coverage: { partial: true, sourcesConfigured: 2, sourcesQueried: 2, sourcesSucceeded: 1, note: 'n' } })
        : makeSearch()),
    });
    renderApp(url);
    expect((await screen.findAllByText('Acme Translator')).length).toBeGreaterThan(0);
    const line = screen.getByText(/Only 1 of 2 queried sources responded/).closest('[role="status"]') as HTMLElement;
    expect(within(line).getByRole('link', { name: 'See which sources failed' })).toHaveAttribute('href', '#h2-sources-h');
    expect(document.querySelector('.h2-source-tile.is-failed')).toHaveTextContent('Beta');
    await userEvent.click(within(line).getByRole('button', { name: /Try again/ }));
    await waitFor(() => expect(screen.queryByText(/Only 1 of 2 queried sources responded/)).not.toBeInTheDocument());
  });

  it('all sources failed: an alert, not an empty result, with retry and the sources page', async () => {
    let n = 0;
    mockApi({
      'GET /search': () => (n++ === 0
        ? makeSearch({ results: [], total: 0, sources: [failed], coverage: { partial: true, sourcesConfigured: 1, sourcesQueried: 1, sourcesSucceeded: 0, note: 'n' }, notices: [{ code: 'ALL_SOURCES_FAILED', message: 'All sources failed.' }] })
        : makeSearch()),
    });
    renderApp(url);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No source responded');
    expect(screen.queryByText('No results from the sources searched')).not.toBeInTheDocument();
    expect(within(alert).getByRole('link', { name: 'See the sources we search' })).toHaveAttribute('href', '/sources');
    await userEvent.click(within(alert).getByRole('button', { name: /Try again/ }));
    expect((await screen.findAllByText('Acme Translator')).length).toBeGreaterThan(0);
  });

  it('search unavailable: an alert with retry that recovers', async () => {
    let n = 0;
    mockApi({ 'GET /search': () => (n++ === 0 ? problem(503, 'Unavailable') : makeSearch()) });
    renderApp(url);
    expect(await screen.findByRole('alert')).toHaveTextContent('Search is unavailable');
    await userEvent.click(screen.getByRole('button', { name: /Try again/ }));
    expect((await screen.findAllByText('Acme Translator')).length).toBeGreaterThan(0);
  });

  it('404: says the page does not exist, points to the search box and the home page', async () => {
    mockApi();
    renderApp('/no/such/page');
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to the home page' })).toHaveAttribute('href', '/');
    expect(screen.getByText(/Search for what you need in the box above/)).toBeInTheDocument();
    expect(screen.getByRole('search')).toBeInTheDocument();
    await settle();
    const v = await axeViolations(document.body);
    expect(v).toEqual([]);
  });

  it('has no axe violations in the partial and empty states', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results: [], total: 0 }) });
    renderApp(url);
    await screen.findByText('No results from the sources searched');
    await settle();
    expect(await axeViolations(document.body)).toEqual([]);
  });
});
