import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN, ANON, USER } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import type { AdminOverview, ReviewItem, ScoutRun } from '@/api/types';
import { RUN_POLL_MS } from '@/pages/admin/ScoutRunsPage';

const item: ReviewItem = {
  id: '66666666-6666-5666-8666-666666666666',
  kind: 'USER_REPORT',
  status: 'OPEN',
  reason: 'Wrong price',
  details: null,
  resourceId: null,
  resourceTitle: 'Acme',
  createdAt: '2026-10-21T10:00:00Z',
};
const page = (items: ReviewItem[], total = items.length) => ({ items, total });
const run: ScoutRun = {
  id: '77777777-7777-5777-8777-777777777777',
  sourceId: 'github',
  status: 'SUCCEEDED',
  startedAt: '2026-10-21T10:00:00Z',
  finishedAt: null,
  itemsSeen: 10,
  itemsChanged: 3,
};

describe('admin: sign-in and role', () => {
  it('offers GitHub sign-in to anonymous visitors without calling admin endpoints', async () => {
    const m = mockApi({ 'GET /me': () => ANON });
    renderApp('/admin/review');
    // The back office loads on demand: wait for its page.
    const main = await screen.findByRole('main');
    expect(await within(main).findByRole('link', { name: 'Sign in with GitHub' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/github',
    );
    expect(within(main).queryByRole('alert')).not.toBeInTheDocument();
    expect(m.callsTo('GET', '/admin/review-items')).toHaveLength(0);
  });

  it('says so when sign-in failed', async () => {
    mockApi({ 'GET /me': () => ANON });
    renderApp('/admin?signin=failed');
    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in did not complete');
    expect(screen.getByRole('link', { name: 'Sign in with GitHub' })).toBeInTheDocument();
  });

  it('lists every provider when several are configured', async () => {
    mockApi({
      'GET /me': () => ({
        ...ANON,
        loginProviders: [
          { id: 'google', name: 'Google' },
          { id: 'github', name: 'GitHub' },
        ],
      }),
    });
    renderApp('/admin');
    await userEvent.click(await screen.findByText('Sign in'));
    expect(screen.getByRole('link', { name: 'Google' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/google',
    );
  });

  it('refuses non-admins on every part without calling admin endpoints, and offers the way home', async () => {
    const m = mockApi({ 'GET /me': () => USER });
    renderApp('/admin/audit');
    expect(await screen.findByRole('alert')).toHaveTextContent('administrator');
    expect(screen.getByRole('link', { name: 'Go to the home page' })).toHaveAttribute('href', '/');
    expect(m.calls.filter((c) => c.path.startsWith('/admin'))).toHaveLength(0);
  });
});

const overview = (over: Partial<AdminOverview> = {}): AdminOverview => ({
  generatedAt: '2026-11-04T09:00:00Z',
  openReviewItems: [
    { kind: 'ENRICHMENT_VALIDATION', count: 3 },
    { kind: 'RELATIONSHIP_CANDIDATE', count: 0 },
    { kind: 'USER_REPORT', count: 12 },
    { kind: 'COURSE_CANDIDATE', count: 1 },
  ],
  enrichmentBacklog: 420,
  budgets: [
    { name: 'enrichment', dailyLimit: 500, usedToday: 120 },
    { name: 'guide', dailyLimit: 200, usedToday: null },
    { name: 'tool-guide', dailyLimit: 0, usedToday: 0 },
  ],
  webCredits: { usedToday: 7 },
  recentRuns: [{ ...run, status: 'FAILED' }],
  failedRunsLast24h: 2,
  homeExamples: {
    shown: [
      { path: 'USE_AI', count: 3 },
      { path: 'BUILD_WITH_AI', count: 2 },
    ],
    hidden: [
      { outcome: 'PENDING', count: 0 },
      { outcome: 'WEAK', count: 0 },
      { outcome: 'FAIL', count: 0 },
    ],
    paused: 0,
  },
  grafanaUrl: 'https://grafana.example/d/helper2',
  ...over,
});

describe('Feature: the overview says what needs a person now', () => {
  it('Scenario: counts per kind open the queue filtered by that kind', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/overview': () => overview(),
      'GET /admin/review-items': () => page([]),
    });
    renderApp('/admin');
    const nav = await screen.findByRole('navigation', { name: 'Back office' });
    expect(within(nav).getByRole('link', { name: 'Overview' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(
      await screen.findByRole('heading', { name: '16 open review items' }),
    ).toBeInTheDocument();
    const reports = screen.getByRole('link', { name: /12 User report/ });
    expect(reports).toHaveAttribute('href', '/admin/review?kind=USER_REPORT');

    await userEvent.click(reports);
    expect(await screen.findByRole('heading', { name: 'Review queue' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Kind' })).toHaveValue('USER_REPORT');
    await waitFor(() =>
      expect(m.callsTo('GET', '/admin/review-items').at(-1)?.search).toBe(
        '?status=OPEN&kind=USER_REPORT&page=0&size=20',
      ),
    );
  });

  it('Scenario: budgets say used of limit in words, off at 0, unknown when not counted; failures link to the runs', async () => {
    mockApi({ 'GET /me': () => ADMIN, 'GET /admin/overview': () => overview() });
    renderApp('/admin');
    const budgets = await screen.findByRole('table', { name: 'Model budgets today (UTC)' });
    expect(within(budgets).getByRole('row', { name: /Enrichment 120 of 500/ })).toBeInTheDocument();
    expect(within(budgets).getByRole('row', { name: /Guide Unknown/ })).toBeInTheDocument();
    expect(within(budgets).getByRole('row', { name: /Setup guide Off/ })).toBeInTheDocument();
    expect(
      within(budgets).getByRole('row', { name: /Web search credits 7, no daily cap/ }),
    ).toBeInTheDocument();
    expect(screen.getByText('420')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: /2 failed runs in the last 24 hours/ }),
    ).toHaveAttribute('href', '/admin/runs');
    expect(screen.getByRole('link', { name: /Trends in Grafana/ })).toHaveAttribute(
      'rel',
      'noopener noreferrer',
    );
    expect(screen.getByText('Failed')).toHaveClass('h2-status-pill--warn');
  });

  it('Scenario: hidden home examples are counted per check and paused, each a link to the examples; a path with fewer than 2 shown warns', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/overview': () =>
        overview({
          homeExamples: {
            shown: [
              { path: 'USE_AI', count: 2 },
              { path: 'BUILD_WITH_AI', count: 1 },
            ],
            hidden: [
              { outcome: 'PENDING', count: 1 },
              { outcome: 'WEAK', count: 2 },
              { outcome: 'FAIL', count: 3 },
            ],
            paused: 4,
          },
        }),
      'GET /admin/home-examples': () => [],
    });
    renderApp('/admin');
    expect(
      await screen.findByRole('heading', { name: '10 home examples hidden' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('Shown now: 2 for people who use AI, 1 for builders.'),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: '2 hidden, expected result only in the top 10' }),
    ).toHaveAttribute('href', '/admin/examples?outcome=WEAK');
    expect(
      screen.getByRole('link', { name: '3 hidden, no expected result in the top 10' }),
    ).toHaveAttribute('href', '/admin/examples?outcome=FAIL');
    expect(screen.getByRole('link', { name: '1 waiting for the check' })).toHaveAttribute(
      'href',
      '/admin/examples?outcome=PENDING',
    );
    expect(screen.getByRole('link', { name: '4 paused' })).toHaveAttribute(
      'href',
      '/admin/examples',
    );
    expect(screen.getByText(/Fewer than 2 shown for Builders/)).toBeInTheDocument();
    expect(screen.queryByText(/Fewer than 2 shown for People who use AI/)).not.toBeInTheDocument();

    await userEvent.click(
      screen.getByRole('link', { name: '3 hidden, no expected result in the top 10' }),
    );
    expect(await screen.findByRole('heading', { name: 'Home examples' })).toBeInTheDocument();
  });

  it('Scenario: with every path well served the overview gives no example warning', async () => {
    mockApi({ 'GET /me': () => ADMIN, 'GET /admin/overview': () => overview() });
    renderApp('/admin');
    expect(
      await screen.findByRole('heading', { name: '0 home examples hidden' }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Fewer than 2 shown/)).not.toBeInTheDocument();
  });

  it('Scenario: without a Grafana link and web credits the overview says so', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/overview': () => overview({ grafanaUrl: null, webCredits: null, recentRuns: [] }),
    });
    renderApp('/admin');
    expect(
      await screen.findByRole('row', { name: /Web search credits Not counted/ }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Grafana/ })).not.toBeInTheDocument();
    expect(screen.getByText('No runs yet.')).toBeInTheDocument();
  });

  it('Scenario: a Grafana link that is not http(s) is not rendered', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/overview': () => overview({ grafanaUrl: 'javascript:alert(1)' }),
    });
    renderApp('/admin');
    await screen.findByRole('heading', { name: '16 open review items' });
    expect(screen.queryByRole('link', { name: /Grafana/ })).not.toBeInTheDocument();
  });

  it('Scenario: Refresh asks again and the overview cannot load says so with Try again', async () => {
    let fail = true;
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/overview': () => (fail ? problem(503, 'Service Unavailable') : overview()),
    });
    renderApp('/admin');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the overview.');
    fail = false;
    await userEvent.click(within(alert).getByRole('button', { name: /Try again/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(m.callsTo('GET', '/admin/overview')).toHaveLength(3));
  });

  it('Scenario: a Refresh that fails keeps the last counts and says they are not current', async () => {
    let fail = false;
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/overview': () => (fail ? problem(503, 'Service Unavailable') : overview()),
    });
    renderApp('/admin');
    const refresh = await screen.findByRole('button', { name: 'Refresh' });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    fail = true;
    await userEvent.click(refresh);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Refresh failed. These counts are from the last load.',
    );
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument();
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });
});

describe('Feature: deciding review items', () => {
  it('Scenario: accept sends the decision and the list reloads', async () => {
    let status: ReviewItem['status'] = 'OPEN';
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page(status === 'OPEN' ? [item] : []),
      'PUT /admin/review-items/*': ({ body }) => {
        status = (body as { status: ReviewItem['status'] }).status;
        return { ...item, status };
      },
    });
    renderApp('/admin/review');
    await userEvent.click(await screen.findByRole('button', { name: /Accept/ }));
    await waitFor(() =>
      expect(m.calls.find((c) => c.method === 'PUT')?.body).toEqual({ status: 'ACCEPTED' }),
    );
    expect(await screen.findByText('Nothing in this queue.')).toBeInTheDocument();
  });

  it('Scenario: reject with a note sends the note', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page([item]),
      'PUT /admin/review-items/*': () => ({ ...item, status: 'REJECTED' }),
    });
    renderApp('/admin/review');
    await userEvent.type(
      await screen.findByRole('textbox', { name: 'Note (optional)' }),
      '  price is right  ',
    );
    await userEvent.click(screen.getByRole('button', { name: /Reject/ }));
    await waitFor(() =>
      expect(m.calls.find((c) => c.method === 'PUT')?.body).toEqual({
        status: 'REJECTED',
        note: 'price is right',
      }),
    );
  });

  it('Scenario: a drafted example that repeats one stays open and says why', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page([item]),
      'PUT /admin/review-items/*': () =>
        new Response(
          JSON.stringify({
            status: 409,
            title: 'Duplicate',
            code: 'duplicate',
            detail: 'Another example already has this label or this query.',
          }),
          { status: 409, headers: { 'content-type': 'application/problem+json' } },
        ),
    });
    renderApp('/admin/review');
    await userEvent.click(await screen.findByRole('button', { name: /Accept/ }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Not accepted, the item stays open');
    expect(alert).toHaveTextContent('Another example already has this label or this query.');
    expect(alert).not.toHaveTextContent('already decided');
    expect(m.callsTo('GET', '/admin/review-items')).toHaveLength(1);
  });

  it('Scenario: an item someone else decided first says so and shows the list as it is now', async () => {
    let decided = false;
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page(decided ? [] : [item]),
      'PUT /admin/review-items/*': () => {
        decided = true;
        return problem(409, 'Conflict');
      },
    });
    renderApp('/admin/review');
    await userEvent.click(await screen.findByRole('button', { name: /Accept/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('already decided');
    expect(await screen.findByText('Nothing in this queue.')).toBeInTheDocument();
    expect(m.callsTo('GET', '/admin/review-items').length).toBeGreaterThan(1);
  });

  it('Scenario: one decision at a time: a second click while a request is pending does nothing', async () => {
    let finish!: () => void;
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () =>
        page([item, { ...item, id: '66666666-6666-5666-8666-666666666667', reason: 'Other' }]),
      'PUT /admin/review-items/*': () =>
        new Promise((r) => {
          finish = () => r({ ...item, status: 'ACCEPTED' });
        }),
    });
    renderApp('/admin/review');
    const [first, second] = await screen.findAllByRole('button', { name: /Accept/ });
    await userEvent.click(first!);
    expect(first).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(second!);
    expect(m.calls.filter((c) => c.method === 'PUT')).toHaveLength(1);
    finish();
    // The list reloads after the decision; its buttons are enabled again.
    await waitFor(() =>
      expect(screen.getAllByRole('button', { name: /Accept/ })[0]).not.toHaveAttribute(
        'aria-disabled',
      ),
    );
  });

  it('Scenario: filters and the page live in the address; a new filter starts on the first page', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': ({ url }) =>
        page([{ ...item, reason: `Item on page ${url.searchParams.get('page')}` }], 45),
    });
    renderApp('/admin/review');
    expect(await screen.findByText('Item on page 0', { selector: 'p' })).toBeInTheDocument();
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Item on page 1', { selector: 'p' })).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'Superseded');
    await waitFor(() =>
      expect(m.callsTo('GET', '/admin/review-items').at(-1)?.search).toBe(
        '?status=SUPERSEDED&page=0&size=20',
      ),
    );
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Kind' }), 'User report');
    await waitFor(() =>
      expect(m.callsTo('GET', '/admin/review-items').at(-1)?.search).toBe(
        '?status=SUPERSEDED&kind=USER_REPORT&page=0&size=20',
      ),
    );
  });

  it('Scenario: an unknown filter in the address falls back to the defaults', async () => {
    const m = mockApi({ 'GET /me': () => ADMIN, 'GET /admin/review-items': () => page([]) });
    renderApp('/admin/review?status=BOGUS&kind=NOPE&page=-3');
    await screen.findByText('Nothing in this queue.');
    expect(m.callsTo('GET', '/admin/review-items').at(-1)?.search).toBe(
      '?status=OPEN&page=0&size=20',
    );
  });

  it('Scenario: a row shows its kind, date and resource, and a closed item its status in words', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () =>
        page([{ ...item, status: 'ACCEPTED', resourceId: 'a/b c', details: 'It is free' }]),
    });
    renderApp('/admin/review?status=ACCEPTED');
    const queue = await screen.findByRole('table', { name: 'Review queue' });
    const row = within(queue).getAllByRole('row')[1] as HTMLElement;
    expect(within(row).getByText('User report')).toBeInTheDocument();
    // The resource link encodes the id, so an id can never add a path segment.
    expect(within(row).getByRole('link', { name: 'Acme' })).toHaveAttribute(
      'href',
      '/resource/a%2Fb%20c',
    );
    expect(within(row).getByText('Accepted')).toHaveClass('h2-status-pill', 'h2-status-pill--ok');
  });
});

describe('Feature: the back office in the terminal style', () => {
  it('Scenario: the review queue says `$ helper2 review --open · N`, with N the open items on the server', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => ({ items: [item], total: 4 }),
    });
    renderApp('/admin/review');
    await screen.findByRole('table', { name: 'Review queue' });
    expect(document.querySelector('.h2-admin__part .v4-list__cmd')).toHaveTextContent(
      '$ helper2 review --open · 4',
    );
  });

  it('Scenario: the Scout runs say `$ helper2 scouts --runs · N`, and Run now per source stays', async () => {
    mockApi({ 'GET /me': () => ADMIN, 'GET /admin/scout-runs': () => [run] });
    renderApp('/admin/runs');
    expect(await screen.findByRole('button', { name: 'Run github now' })).toBeInTheDocument();
    expect(document.querySelector('.h2-admin__part .v4-list__cmd')).toHaveTextContent(
      '$ helper2 scouts --runs · 1',
    );
  });
});

describe('Feature: the review queue cannot load', () => {
  it('Scenario: Try again keeps focus on the queue and shows the items', async () => {
    let fail = true;
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => (fail ? problem(503, 'Service Unavailable') : page([item])),
    });
    renderApp('/admin/review');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the review queue.');
    fail = false;
    within(alert)
      .getByRole('button', { name: /Try again/ })
      .focus();
    await userEvent.keyboard('{Enter}');
    expect(await screen.findByRole('button', { name: /Accept/ })).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Review queue' })).toHaveFocus(),
    );
  });
});

describe('Feature: Scout runs', () => {
  it('Scenario: run all now starts every collector and reloads the listing', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/scout-runs': () => [run],
      'POST /admin/scout-runs': () => [],
    });
    renderApp('/admin/runs');
    expect(await screen.findByText('github')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Run all now' }));
    await waitFor(() => expect(m.callsTo('POST', '/admin/scout-runs')).toHaveLength(1));
    expect(m.callsTo('POST', '/admin/scout-runs')[0]?.body).toEqual({});
    await waitFor(() => expect(m.callsTo('GET', '/admin/scout-runs').length).toBeGreaterThan(1));
  });

  it('Scenario: run now on a source starts only that source and reloads the listing', async () => {
    const older = { ...run, id: '88888888-8888-5888-8888-888888888888' };
    const npm = { ...run, id: '99999999-9999-5999-8999-999999999999', sourceId: 'npm' };
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/scout-runs': () => [run, npm, older],
      'POST /admin/scout-runs': () => [],
    });
    renderApp('/admin/runs');
    // One Run now per source, on its newest run.
    expect(await screen.findAllByRole('button', { name: 'Run github now' })).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'Run npm now' }));
    await waitFor(() =>
      expect(m.callsTo('POST', '/admin/scout-runs')[0]?.body).toEqual({ sourceId: 'npm' }),
    );
    await waitFor(() => expect(m.callsTo('GET', '/admin/scout-runs').length).toBeGreaterThan(1));
  });

  it('Scenario: while a run is running, every start waits and says why', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/scout-runs': () => [{ ...run, status: 'RUNNING' }],
    });
    renderApp('/admin/runs');
    const one = await screen.findByRole('button', { name: 'Run github now' });
    const all = screen.getByRole('button', { name: 'Run all now' });
    expect(one).toHaveAttribute('aria-disabled', 'true');
    expect(all).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByText(/A run is in progress/)).toBeInTheDocument();
    await userEvent.click(one);
    await userEvent.click(all);
    expect(m.callsTo('POST', '/admin/scout-runs')).toHaveLength(0);
  });

  it('Scenario: a start for one source that fails says so', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/scout-runs': () => [run],
      'POST /admin/scout-runs': () => problem(500, 'Boom'),
    });
    renderApp('/admin/runs');
    await userEvent.click(await screen.findByRole('button', { name: 'Run github now' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('The action failed');
  });

  it('Scenario: the runs cannot load: it says so with Try again, not "No runs yet"', async () => {
    let fail = true;
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/scout-runs': () => (fail ? problem(500, 'Boom') : [run]),
    });
    renderApp('/admin/runs');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the Scout runs.');
    expect(screen.queryByText('No runs yet.')).not.toBeInTheDocument();
    fail = false;
    await userEvent.click(within(alert).getByRole('button', { name: /Try again/ }));
    expect(await screen.findByText('github')).toBeInTheDocument();
  });

  it('Scenario: a reload that fails while following a run keeps the list and says so', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    try {
      let fail = false;
      mockApi({
        'GET /me': () => ADMIN,
        'GET /admin/scout-runs': () =>
          fail ? problem(500, 'Boom') : [{ ...run, status: 'RUNNING' }],
      });
      renderApp('/admin/runs');
      expect(await screen.findByText('Running')).toBeInTheDocument();
      fail = true;
      await act(() => vi.advanceTimersByTimeAsync(RUN_POLL_MS));
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Could not reload the Scout runs. The list is from the last load.',
      );
      expect(screen.getByText('github')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('Scenario: a started run is followed on the listing until it ends', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    try {
      const started = { ...run, sourceId: 'mcp-registry', itemsSeen: 0, itemsChanged: 0 };
      let listed: ScoutRun[] = [];
      const m = mockApi({
        'GET /me': () => ADMIN,
        'GET /admin/scout-runs': () => listed,
        'POST /admin/scout-runs': () => {
          listed = [{ ...started, status: 'RUNNING' }];
          return listed;
        },
      });
      renderApp('/admin/runs');
      await userEvent.click(await screen.findByRole('button', { name: 'Run all now' }));
      expect(await screen.findByText('Running')).toBeInTheDocument();

      listed = [{ ...started, status: 'SUCCEEDED', itemsSeen: 4 }];
      await act(() => vi.advanceTimersByTimeAsync(RUN_POLL_MS));
      expect(await screen.findByText('Succeeded')).toBeInTheDocument();
      const polls = m.callsTo('GET', '/admin/scout-runs').length;
      await act(() => vi.advanceTimersByTimeAsync(RUN_POLL_MS * 2));
      expect(m.callsTo('GET', '/admin/scout-runs')).toHaveLength(polls);
    } finally {
      vi.useRealTimers();
    }
  });

  it('Scenario: a start refused while a run holds the lock says a run is in progress', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/scout-runs': () => [],
      'POST /admin/scout-runs': () => problem(409, 'Conflict'),
    });
    renderApp('/admin/runs');
    await userEvent.click(await screen.findByRole('button', { name: 'Run all now' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'A Scout run is already in progress',
    );
  });

  it('Scenario: a second click while the start is pending does nothing and keeps focus', async () => {
    let finish!: () => void;
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/scout-runs': () => [],
      'POST /admin/scout-runs': () =>
        new Promise((r) => {
          finish = () => r([]);
        }),
    });
    renderApp('/admin/runs');
    const button = await screen.findByRole('button', { name: 'Run all now' });
    await userEvent.click(button);
    expect(button).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(button);
    expect(button).toHaveFocus();
    expect(m.callsTo('POST', '/admin/scout-runs')).toHaveLength(1);
    finish();
    await waitFor(() => expect(button).not.toHaveAttribute('aria-disabled'));
  });
});

describe('Feature: the audit trail', () => {
  it('Scenario: entries are listed newest first with the action in words, paged in the address', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/audit': ({ url }) => ({
        items: [
          {
            id: '99999999-9999-5999-8999-999999999999',
            adminId: '12345',
            action: 'RESOLVE_REVIEW_ITEM',
            targetId: item.id,
            at: '2026-11-01T09:00:00Z',
            outcome: `NOT_OPEN ${url.searchParams.get('page')}`,
          },
        ],
        total: 25,
      }),
    });
    renderApp('/admin/audit');
    const table = await screen.findByRole('table', { name: 'Audit trail' });
    expect(within(table).getByText('Review decision')).toBeInTheDocument();
    expect(within(table).getByText('NOT_OPEN 0')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('NOT_OPEN 1')).toBeInTheDocument();
    expect(m.callsTo('GET', '/admin/audit').at(-1)?.search).toBe('?page=1&size=20');
  });

  it('Scenario: the trail cannot load and offers Try again', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/audit': () => problem(503, 'Service Unavailable'),
    });
    renderApp('/admin/audit');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the audit trail.');
    expect(within(alert).getByRole('button', { name: /Try again/ })).toBeInTheDocument();
  });
});
