import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN, ANON, USER } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import type { ReviewItem, ScoutRun } from '@/api/types';
import { RUN_POLL_MS } from '@/pages/admin/ScoutRunsPage';

const item: ReviewItem = { id: '66666666-6666-5666-8666-666666666666', kind: 'USER_REPORT', status: 'OPEN', reason: 'Wrong price', details: null, resourceId: null, resourceTitle: 'Acme', createdAt: '2026-10-21T10:00:00Z' };
const page = (items: ReviewItem[], total = items.length) => ({ items, total });
const run: ScoutRun = { id: '77777777-7777-5777-8777-777777777777', sourceId: 'github', status: 'SUCCEEDED', startedAt: '2026-10-21T10:00:00Z', finishedAt: null, itemsSeen: 10, itemsChanged: 3 };

describe('admin: sign-in and role', () => {
  it('offers GitHub sign-in to anonymous visitors without calling admin endpoints', async () => {
    const m = mockApi({ 'GET /me': () => ANON });
    renderApp('/admin/review');
    const main = screen.getByRole('main');
    expect(await within(main).findByRole('link', { name: 'Sign in with GitHub' })).toHaveAttribute('href', '/oauth2/authorization/github');
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
    mockApi({ 'GET /me': () => ({ ...ANON, loginProviders: [{ id: 'google', name: 'Google' }, { id: 'github', name: 'GitHub' }] }) });
    renderApp('/admin');
    await userEvent.click(await screen.findByText('Sign in'));
    expect(screen.getByRole('link', { name: 'Google' })).toHaveAttribute('href', '/oauth2/authorization/google');
  });

  it('refuses non-admins on every part without calling admin endpoints, and offers the way home', async () => {
    const m = mockApi({ 'GET /me': () => USER });
    renderApp('/admin/audit');
    expect(await screen.findByRole('alert')).toHaveTextContent('administrator');
    expect(screen.getByRole('link', { name: 'Go to the home page' })).toHaveAttribute('href', '/');
    expect(m.calls.filter((c) => c.path.startsWith('/admin'))).toHaveLength(0);
  });
});

describe('Feature: the back office has a part per page', () => {
  it('Scenario: the overview counts open items per kind, and a count opens the queue filtered by that kind', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': ({ url }) => page([], url.searchParams.get('kind') === 'SOURCE_CONFLICT' ? 12 : 0),
      'GET /admin/scout-runs': () => [{ ...run, status: 'FAILED' }],
    });
    renderApp('/admin');
    const nav = await screen.findByRole('navigation', { name: 'Back office' });
    expect(within(nav).getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page');
    const conflicts = await screen.findByRole('link', { name: /12 Source conflict/ });
    expect(conflicts).toHaveAttribute('href', '/admin/review?kind=SOURCE_CONFLICT');
    expect(await screen.findByText('Failed')).toHaveClass('h2-status-pill--warn');

    await userEvent.click(conflicts);
    expect(await screen.findByRole('heading', { name: 'Review queue' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Kind' })).toHaveValue('SOURCE_CONFLICT');
    await waitFor(() => expect(m.callsTo('GET', '/admin/review-items').at(-1)?.search).toBe('?status=OPEN&kind=SOURCE_CONFLICT&page=0&size=20'));
  });
});

describe('Feature: deciding review items', () => {
  it('Scenario: accept sends the decision and the list reloads', async () => {
    let status: ReviewItem['status'] = 'OPEN';
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page(status === 'OPEN' ? [item] : []),
      'PUT /admin/review-items/*': ({ body }) => { status = (body as { status: ReviewItem['status'] }).status; return { ...item, status }; },
    });
    renderApp('/admin/review');
    await userEvent.click(await screen.findByRole('button', { name: /Accept/ }));
    await waitFor(() => expect(m.calls.find((c) => c.method === 'PUT')?.body).toEqual({ status: 'ACCEPTED' }));
    expect(await screen.findByText('Nothing in this queue.')).toBeInTheDocument();
  });

  it('Scenario: reject with a note sends the note', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page([item]),
      'PUT /admin/review-items/*': () => ({ ...item, status: 'REJECTED' }),
    });
    renderApp('/admin/review');
    await userEvent.type(await screen.findByRole('textbox', { name: 'Note (optional)' }), '  price is right  ');
    await userEvent.click(screen.getByRole('button', { name: /Reject/ }));
    await waitFor(() => expect(m.calls.find((c) => c.method === 'PUT')?.body).toEqual({ status: 'REJECTED', note: 'price is right' }));
  });

  it('Scenario: an item someone else decided first says so and shows the list as it is now', async () => {
    let decided = false;
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page(decided ? [] : [item]),
      'PUT /admin/review-items/*': () => { decided = true; return problem(409, 'Conflict'); },
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
      'GET /admin/review-items': () => page([item, { ...item, id: '66666666-6666-5666-8666-666666666667', reason: 'Other' }]),
      'PUT /admin/review-items/*': () => new Promise((r) => { finish = () => r({ ...item, status: 'ACCEPTED' }); }),
    });
    renderApp('/admin/review');
    const [first, second] = await screen.findAllByRole('button', { name: /Accept/ });
    await userEvent.click(first!);
    expect(first).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(second!);
    expect(m.calls.filter((c) => c.method === 'PUT')).toHaveLength(1);
    finish();
    // The list reloads after the decision; its buttons are enabled again.
    await waitFor(() => expect(screen.getAllByRole('button', { name: /Accept/ })[0]).not.toHaveAttribute('aria-disabled'));
  });

  it('Scenario: filters and the page live in the address; a new filter starts on the first page', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': ({ url }) => page([{ ...item, reason: `Item on page ${url.searchParams.get('page')}` }], 45),
    });
    renderApp('/admin/review');
    expect(await screen.findByText('Item on page 0', { selector: 'p' })).toBeInTheDocument();
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Item on page 1', { selector: 'p' })).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'Superseded');
    await waitFor(() => expect(m.callsTo('GET', '/admin/review-items').at(-1)?.search).toBe('?status=SUPERSEDED&page=0&size=20'));
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Kind' }), 'User report');
    await waitFor(() => expect(m.callsTo('GET', '/admin/review-items').at(-1)?.search).toBe('?status=SUPERSEDED&kind=USER_REPORT&page=0&size=20'));
  });

  it('Scenario: an unknown filter in the address falls back to the defaults', async () => {
    const m = mockApi({ 'GET /me': () => ADMIN, 'GET /admin/review-items': () => page([]) });
    renderApp('/admin/review?status=BOGUS&kind=NOPE&page=-3');
    await screen.findByText('Nothing in this queue.');
    expect(m.callsTo('GET', '/admin/review-items').at(-1)?.search).toBe('?status=OPEN&page=0&size=20');
  });

  it('Scenario: a row shows its kind, date and resource, and a closed item its status as a pill', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page([{ ...item, status: 'ACCEPTED', resourceId: 'a/b c', details: 'It is free' }]),
    });
    renderApp('/admin/review?status=ACCEPTED');
    const queue = await screen.findByRole('list', { name: 'Review queue' });
    const row = within(queue).getByRole('listitem');
    expect(within(row).getByText('User report')).toBeInTheDocument();
    // The resource link encodes the id, so an id can never add a path segment.
    expect(within(row).getByRole('link', { name: 'Acme' })).toHaveAttribute('href', '/resource/a%2Fb%20c');
    expect(within(row).getByText('Accepted')).toHaveClass('h2-status-pill', 'h2-status-pill--ok');
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
    within(alert).getByRole('button', { name: /Try again/ }).focus();
    await userEvent.keyboard('{Enter}');
    expect(await screen.findByRole('button', { name: /Accept/ })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review queue' })).toHaveFocus());
  });
});

describe('Feature: Scout runs', () => {
  it('Scenario: run now starts a run and reloads the listing', async () => {
    const m = mockApi({ 'GET /me': () => ADMIN, 'GET /admin/scout-runs': () => [run], 'POST /admin/scout-runs': () => [] });
    renderApp('/admin/runs');
    expect(await screen.findByText('github')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }));
    await waitFor(() => expect(m.callsTo('POST', '/admin/scout-runs')).toHaveLength(1));
    await waitFor(() => expect(m.callsTo('GET', '/admin/scout-runs').length).toBeGreaterThan(1));
  });

  it('Scenario: a started run is followed on the listing until it ends', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    try {
      const started = { ...run, sourceId: 'mcp-registry', itemsSeen: 0, itemsChanged: 0 };
      let listed: ScoutRun[] = [];
      const m = mockApi({
        'GET /me': () => ADMIN,
        'GET /admin/scout-runs': () => listed,
        'POST /admin/scout-runs': () => { listed = [{ ...started, status: 'RUNNING' }]; return listed; },
      });
      renderApp('/admin/runs');
      await userEvent.click(await screen.findByRole('button', { name: 'Run now' }));
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
    mockApi({ 'GET /me': () => ADMIN, 'GET /admin/scout-runs': () => [], 'POST /admin/scout-runs': () => problem(409, 'Conflict') });
    renderApp('/admin/runs');
    await userEvent.click(await screen.findByRole('button', { name: 'Run now' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A Scout run is already in progress');
  });

  it('Scenario: a second click while the start is pending does nothing and keeps focus', async () => {
    let finish!: () => void;
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/scout-runs': () => [],
      'POST /admin/scout-runs': () => new Promise((r) => { finish = () => r([]); }),
    });
    renderApp('/admin/runs');
    const button = await screen.findByRole('button', { name: 'Run now' });
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
        items: [{ id: '99999999-9999-5999-8999-999999999999', adminId: '12345', action: 'RESOLVE_REVIEW_ITEM', targetId: item.id, at: '2026-11-01T09:00:00Z', outcome: `NOT_OPEN ${url.searchParams.get('page')}` }],
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
    mockApi({ 'GET /me': () => ADMIN, 'GET /admin/audit': () => problem(503, 'Service Unavailable') });
    renderApp('/admin/audit');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the audit trail.');
    expect(within(alert).getByRole('button', { name: /Try again/ })).toBeInTheDocument();
  });
});
