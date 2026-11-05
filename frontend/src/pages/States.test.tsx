import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { makeSearch, makeStatus } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const url = '/search?q=translate';
const failed = makeStatus({
  sourceId: 'b',
  name: 'Beta',
  outcome: 'FAILED',
  servedFrom: 'NONE',
  latencyMs: null,
});

describe('states: every state says what happened and offers a next step', () => {
  it('loading: a status with skeleton rows shaped like the list', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => {
      release = r;
    });
    mockApi({
      'GET /search': async () => {
        await gate;
        return makeSearch();
      },
    });
    renderApp(url);
    const status = (await screen.findByText('Searching the listed sources…')).closest(
      '[role="status"]',
    );
    expect(status).not.toBeNull();
    expect(document.querySelectorAll('.h2-skeleton__row')).toHaveLength(4);
    release();
    expect((await screen.findAllByText('Acme Translator')).length).toBeGreaterThan(0);
    expect(document.querySelector('.h2-skeleton')).toBeNull();
  });

  it('guide answering: the list is already there while the picks bar reads', async () => {
    mockApi({
      'GET /search': () => makeSearch(),
      'POST /guide/stream': () => new Promise<Response>(() => undefined),
    });
    renderApp(`${url}&ask=translate+documents+for+free`);
    expect((await screen.findAllByText('Acme Translator')).length).toBeGreaterThan(0);
    expect(
      screen.getByText('Guide is reading the results… picks will appear here'),
    ).toBeInTheDocument();
  });

  it('no results: asks the guide or opens the sources', async () => {
    let n = 0;
    mockApi({
      'GET /search': () => makeSearch({ results: [], total: 0 }),
      'POST /guide/stream': () => {
        n++;
        return new Promise<Response>(() => undefined);
      },
    });
    const { params } = renderApp(url);
    const empty = (await screen.findByText('No results from the sources searched')).closest(
      '[role="status"]',
    ) as HTMLElement;
    expect(empty).toHaveTextContent('Try different or fewer words.');
    expect(within(empty).getByRole('link', { name: 'See the sources we search' })).toHaveAttribute(
      'href',
      '/sources',
    );
    await userEvent.click(within(empty).getByRole('button', { name: 'Ask the guide instead' }));
    await waitFor(() => expect(params().get('ask')).toBe('translate'));
    await waitFor(() => expect(n).toBe(1));
  });

  it('no results for a search too short to ask: no guide button that would do nothing', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results: [], total: 0 }) });
    renderApp('/search?q=ai');
    const empty = (await screen.findByText('No results from the sources searched')).closest(
      '[role="status"]',
    ) as HTMLElement;
    expect(
      within(empty).queryByRole('button', { name: 'Ask the guide instead' }),
    ).not.toBeInTheDocument();
    expect(
      within(empty).getByRole('link', { name: 'See the sources we search' }),
    ).toBeInTheDocument();
  });

  it('some sources failed: results stay, one quiet line says so, Which? names them, a retry can help', async () => {
    let n = 0;
    mockApi({
      'GET /search': () =>
        n++ === 0
          ? makeSearch({
              sources: [makeStatus(), failed],
              coverage: {
                partial: true,
                sourcesConfigured: 2,
                sourcesQueried: 2,
                sourcesSucceeded: 1,
                note: 'n',
              },
            })
          : makeSearch(),
    });
    renderApp(url);
    expect((await screen.findAllByText('Acme Translator')).length).toBeGreaterThan(0);
    const row = screen
      .getByText(/1 of 2 sources did not answer/)
      .closest('.h2-list__count') as HTMLElement;
    expect(row).toHaveTextContent('1 result · 1 of 2 sources did not answer');
    // No banner, no box.
    expect(document.querySelector('.banner')).toBeNull();
    // The sources and why, only on demand.
    const which = within(row).getByRole('button', { name: 'Which?' });
    expect(which).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('returned an error.')).not.toBeVisible();
    await userEvent.click(which);
    expect(which).toHaveAttribute('aria-expanded', 'true');
    const list = document.getElementById(which.getAttribute('aria-controls') ?? '') as HTMLElement;
    expect(list).toBeVisible();
    expect(list).toHaveTextContent('Beta returned an error.');
    expect(document.querySelector('.h2-source-tile.is-failed')).toHaveTextContent('Beta');
    // An error can pass: Try again is offered, and a full answer removes the line.
    await userEvent.click(within(row).getByRole('button', { name: 'Try again' }));
    await waitFor(() =>
      expect(screen.queryByText(/sources did not answer/)).not.toBeInTheDocument(),
    );
  });

  it('a paused source or an open circuit: the line says why, and offers no Try again', async () => {
    mockApi({
      'GET /search': () =>
        makeSearch({
          sources: [
            makeStatus(),
            makeStatus({
              sourceId: 'arxiv',
              name: 'arXiv',
              outcome: 'SKIPPED',
              servedFrom: 'NONE',
              skipReason: 'RATE_LIMIT_PAUSE',
              latencyMs: null,
            }),
            makeStatus({
              sourceId: 'hf',
              name: 'Hugging Face',
              outcome: 'SKIPPED',
              servedFrom: 'STALE_CACHE',
              skipReason: 'CIRCUIT_OPEN',
              latencyMs: null,
            }),
            makeStatus({
              sourceId: 'npm',
              name: 'npm',
              outcome: 'RATE_LIMITED',
              servedFrom: 'NONE',
              latencyMs: null,
            }),
            // Left out on purpose: not counted, not listed.
            makeStatus({
              sourceId: 'off',
              name: 'Off',
              outcome: 'SKIPPED',
              servedFrom: 'NONE',
              skipReason: 'DISABLED',
              latencyMs: null,
            }),
          ],
          coverage: {
            partial: true,
            sourcesConfigured: 5,
            sourcesQueried: 4,
            sourcesSucceeded: 1,
            note: 'n',
          },
        }),
    });
    renderApp(url);
    const row = (await screen.findByText(/3 of 4 sources did not answer/)).closest(
      '.h2-list__count',
    ) as HTMLElement;
    expect(within(row).queryByRole('button', { name: /Try again/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Try again/ })).not.toBeInTheDocument();
    await userEvent.click(within(row).getByRole('button', { name: 'Which?' }));
    const items = within(row)
      .getAllByRole('listitem')
      .map((li) => li.textContent);
    expect(items).toEqual([
      'arXiv is paused for a while after a rate limit.',
      'Hugging Face is paused after repeated errors. Saved results from it are shown.',
      'npm hit its rate limit and is paused for a while.',
    ]);
  });

  it('a partial answer is not a live announcement: only the count is a live region', async () => {
    mockApi({
      'GET /search': () =>
        makeSearch({
          sources: [makeStatus(), failed],
          coverage: {
            partial: true,
            sourcesConfigured: 2,
            sourcesQueried: 2,
            sourcesSucceeded: 1,
            note: 'n',
          },
        }),
    });
    renderApp(url);
    const line = await screen.findByText(/1 of 2 sources did not answer/);
    expect(line.closest('[aria-live], [role="status"], [role="alert"]')).toBeNull();
    const live = Array.from(
      document.querySelectorAll('[aria-live], [role="status"], [role="alert"]'),
    );
    expect(live.some((el) => /did not answer/.test(el.textContent ?? ''))).toBe(false);
    // The count is the one polite live region of the list.
    expect(screen.getByText('1 result')).toHaveAttribute('aria-live', 'polite');
  });

  it('all sources failed: an alert, not an empty result, with retry and the sources page', async () => {
    let n = 0;
    mockApi({
      'GET /search': () =>
        n++ === 0
          ? makeSearch({
              results: [],
              total: 0,
              sources: [failed],
              coverage: {
                partial: true,
                sourcesConfigured: 1,
                sourcesQueried: 1,
                sourcesSucceeded: 0,
                note: 'n',
              },
              notices: [{ code: 'ALL_SOURCES_FAILED', message: 'All sources failed.' }],
            })
          : makeSearch(),
    });
    renderApp(url);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No source responded');
    expect(screen.queryByText('No results from the sources searched')).not.toBeInTheDocument();
    expect(within(alert).getByRole('link', { name: 'See the sources we search' })).toHaveAttribute(
      'href',
      '/sources',
    );
    await userEvent.click(within(alert).getByRole('button', { name: /Try again/ }));
    expect((await screen.findAllByText('Acme Translator')).length).toBeGreaterThan(0);
  });

  it('all sources paused: still a prominent alert, but no Try again that cannot help', async () => {
    mockApi({
      'GET /search': () =>
        makeSearch({
          results: [],
          total: 0,
          sources: [
            makeStatus({
              sourceId: 'arxiv',
              name: 'arXiv',
              outcome: 'SKIPPED',
              servedFrom: 'NONE',
              skipReason: 'CIRCUIT_OPEN',
              latencyMs: null,
            }),
          ],
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
    renderApp(url);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No source responded');
    expect(within(alert).queryByRole('button', { name: /Try again/ })).not.toBeInTheDocument();
    expect(
      within(alert).getByRole('link', { name: 'See the sources we search' }),
    ).toBeInTheDocument();
  });

  it('partial and empty: the quiet line sits above the empty state', async () => {
    mockApi({
      'GET /search': () =>
        makeSearch({
          results: [],
          total: 0,
          sources: [makeStatus({ resultCount: 0 }), failed],
          coverage: {
            partial: true,
            sourcesConfigured: 2,
            sourcesQueried: 2,
            sourcesSucceeded: 1,
            note: 'n',
          },
        }),
    });
    renderApp(url);
    expect(await screen.findByText('No results from the sources searched')).toBeInTheDocument();
    expect(screen.getByText(/1 of 2 sources did not answer/)).toBeInTheDocument();
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
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument();
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
