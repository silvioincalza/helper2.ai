import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN, ANON, USER } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import type { ReviewItem, ScoutRun } from '@/api/types';
import { RUN_POLL_MS } from '@/pages/AdminPage';

const item: ReviewItem = { id: '66666666-6666-5666-8666-666666666666', kind: 'USER_REPORT', status: 'OPEN', reason: 'Wrong price', details: null, resourceId: null, resourceTitle: 'Acme', createdAt: '2026-10-21T10:00:00Z' };
const page = (items: ReviewItem[], total = items.length) => ({ items, total });

describe('admin page', () => {
  it('offers GitHub sign-in to anonymous visitors without calling admin endpoints', async () => {
    const m = mockApi({ 'GET /me': () => ANON });
    renderApp('/admin');
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

  it('refuses non-admins without calling admin endpoints', async () => {
    const m = mockApi({ 'GET /me': () => USER });
    renderApp('/admin');
    expect(await screen.findByRole('alert')).toHaveTextContent('administrator');
    expect(m.callsTo('GET', '/admin/review-items')).toHaveLength(0);
  });

  it('accepts a review item and reloads', async () => {
    let status: ReviewItem['status'] = 'OPEN';
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page(status === 'OPEN' ? [item] : []),
      'GET /admin/scout-runs': () => [],
      'PUT /admin/review-items/*': ({ body }) => { status = (body as { status: ReviewItem['status'] }).status; return { ...item, status }; },
    });
    renderApp('/admin');
    await userEvent.click(await screen.findByRole('button', { name: /Accept/ }));
    await waitFor(() => expect(m.calls.find((c) => c.method === 'PUT')?.body).toEqual({ status: 'ACCEPTED' }));
    expect(await screen.findByText('Nothing in this queue.')).toBeInTheDocument();
  });

  it('rejects, and runs the scout now', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page([item]),
      'GET /admin/scout-runs': () => [{ id: '77777777-7777-5777-8777-777777777777', sourceId: 'github', status: 'SUCCEEDED', startedAt: '2026-10-21T10:00:00Z', finishedAt: null, itemsSeen: 10, itemsChanged: 3 }],
      'PUT /admin/review-items/*': () => ({ ...item, status: 'REJECTED' }),
      'POST /admin/scout-runs': () => [],
    });
    renderApp('/admin');
    await userEvent.click(await screen.findByRole('button', { name: /Reject/ }));
    await waitFor(() => expect(m.calls.find((c) => c.method === 'PUT')?.body).toEqual({ status: 'REJECTED' }));
    expect(await screen.findByText('github')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }));
    await waitFor(() => expect(m.callsTo('POST', '/admin/scout-runs')).toHaveLength(1));
    await waitFor(() => expect(m.callsTo('GET', '/admin/scout-runs').length).toBeGreaterThan(1));
  });

  it('follows a started run on the listing until it ends', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    try {
      const run = { id: '88888888-8888-5888-8888-888888888888', sourceId: 'mcp-registry', startedAt: '2026-10-21T10:00:00Z', finishedAt: null, itemsSeen: 0, itemsChanged: 0 };
      let listed: (typeof run & { status: ScoutRun['status'] })[] = [];
      const m = mockApi({
        'GET /me': () => ADMIN,
        'GET /admin/review-items': () => page([]),
        'GET /admin/scout-runs': () => listed,
        'POST /admin/scout-runs': () => { listed = [{ ...run, status: 'RUNNING' }]; return listed; },
      });
      renderApp('/admin');
      await userEvent.click(await screen.findByRole('button', { name: 'Run now' }));
      expect(await screen.findByText('Running')).toBeInTheDocument();

      listed = [{ ...run, status: 'SUCCEEDED', itemsSeen: 4 }];
      await act(() => vi.advanceTimersByTimeAsync(RUN_POLL_MS));
      expect(await screen.findByText('Succeeded')).toBeInTheDocument();
      const polls = m.callsTo('GET', '/admin/scout-runs').length;
      await act(() => vi.advanceTimersByTimeAsync(RUN_POLL_MS * 2));
      expect(m.callsTo('GET', '/admin/scout-runs')).toHaveLength(polls);
    } finally {
      vi.useRealTimers();
    }
  });

  it('says a run is in progress when the start is refused', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page([]),
      'GET /admin/scout-runs': () => [],
      'POST /admin/scout-runs': () => problem(409, 'Conflict'),
    });
    renderApp('/admin');
    await userEvent.click(await screen.findByRole('button', { name: 'Run now' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A Scout run is already in progress');
  });

  it('pages through the queue and starts again on the first page when the status changes', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': ({ url }) => page([{ ...item, reason: `Item on page ${url.searchParams.get('page')}` }], 45),
      'GET /admin/scout-runs': () => [],
    });
    renderApp('/admin');
    expect(await screen.findByText('Item on page 0', { selector: 'p' })).toBeInTheDocument();
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Item on page 1', { selector: 'p' })).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'Superseded');
    await waitFor(() => expect(m.callsTo('GET', '/admin/review-items').at(-1)?.search).toBe('?status=SUPERSEDED&page=0&size=20'));
  });

  it('runs one action at a time: a second click while a request is pending does nothing', async () => {
    let finish!: () => void;
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page([item]),
      'GET /admin/scout-runs': () => [],
      'POST /admin/scout-runs': () => new Promise((r) => { finish = () => r([]); }),
      'PUT /admin/review-items/*': () => ({ ...item, status: 'ACCEPTED' }),
    });
    renderApp('/admin');
    const run = await screen.findByRole('button', { name: 'Run now' });
    await userEvent.click(run);
    expect(run).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('button', { name: /Accept/ })).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(run);
    expect(run).toHaveFocus();
    await userEvent.click(screen.getByRole('button', { name: /Accept/ }));
    expect(m.callsTo('POST', '/admin/scout-runs')).toHaveLength(1);
    expect(m.calls.filter((c) => c.method === 'PUT')).toHaveLength(0);
    finish();
    await waitFor(() => expect(run).not.toHaveAttribute('aria-disabled'));
  });
});

describe('Feature: the review queue cannot load', () => {
  it('Scenario: Try again keeps focus on the queue and shows the items', async () => {
    // Given the review queue fails once
    let fail = true;
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => (fail ? problem(503, 'Service Unavailable') : page([item])),
      'GET /admin/scout-runs': () => [],
    });
    renderApp('/admin');
    // Then an alert says so and offers Try again
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByRole('heading', { name: 'Could not load the review queue' })).toBeInTheDocument();
    // When the admin tries again by keyboard
    fail = false;
    within(alert).getByRole('button', { name: 'Try again' }).focus();
    await userEvent.keyboard('{Enter}');
    // Then the items load and focus stays on the queue's heading, not the page body
    expect(await screen.findByRole('button', { name: /Accept/ })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review queue' })).toHaveFocus());
  });
});

describe('Feature: links to resources', () => {
  it('Scenario: a review item links to its resource page with the id encoded', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page([{ ...item, resourceId: 'a/b c', resourceTitle: 'Acme' }]),
      'GET /admin/scout-runs': () => [],
    });
    renderApp('/admin');
    expect(await screen.findByRole('link', { name: 'Acme' })).toHaveAttribute('href', '/resource/a%2Fb%20c');
  });
});
