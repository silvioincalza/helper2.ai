import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ANON, USER } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

describe('Feature: delete my account', () => {
  it('Scenario: a signed-in user opens their account from their name in the header', async () => {
    mockApi({ 'GET /me': () => USER });
    const { router } = renderApp('/');
    await userEvent.click(
      await within(screen.getByRole('banner')).findByRole('link', { name: 'Ada' }),
    );
    // The account page loads on demand: the address changes once it is there.
    await waitFor(() => expect(router.state.location.pathname).toBe('/account'));
    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
  });

  it('Scenario: the user confirms, the account is deleted and the page starts over', async () => {
    // Given a signed-in user on their account page
    const reload = vi.fn();
    vi.stubGlobal('location', { ...window.location, reload });
    const m = mockApi({
      'GET /me': () => USER,
      'DELETE /me': () => new Response(null, { status: 204 }),
    });
    renderApp('/account');
    // When they ask to delete the account
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    // Then a dialog asks them to confirm, and nothing is deleted yet
    const dialog = await screen.findByRole('alertdialog', { name: 'Delete your account?' });
    expect(m.callsTo('DELETE', '/me')).toHaveLength(0);
    // When they confirm
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete account' }));
    // Then the account is deleted and the page reloads signed out
    await waitFor(() => expect(m.callsTo('DELETE', '/me')).toHaveLength(1));
    await waitFor(() => expect(reload).toHaveBeenCalled());
  });

  it('Scenario: the user cancels and nothing is deleted', async () => {
    const m = mockApi({ 'GET /me': () => USER });
    renderApp('/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    await userEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Cancel' }),
    );
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(m.callsTo('DELETE', '/me')).toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Delete my account' })).toHaveFocus();
  });

  it('Scenario: Escape closes the dialog, which opened on Cancel', async () => {
    mockApi({ 'GET /me': () => USER });
    renderApp('/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    expect(
      within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Cancel' }),
    ).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('Scenario: the server refuses and the user is told', async () => {
    const reload = vi.fn();
    vi.stubGlobal('location', { ...window.location, reload });
    mockApi({ 'GET /me': () => USER, 'DELETE /me': () => problem(503, 'Service Unavailable') });
    renderApp('/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    await userEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', {
        name: 'Delete account',
      }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('Your account could not be deleted');
    expect(reload).not.toHaveBeenCalled();
  });

  it('Scenario: while the page asks who is signed in, it says it is loading', async () => {
    let answer: (v: unknown) => void = () => {};
    mockApi({
      'GET /me': () =>
        new Promise((r) => {
          answer = r;
        }),
    });
    renderApp('/account');
    // The page loads on demand, then waits for GET /me.
    expect(await within(await screen.findByRole('main')).findByRole('status')).toHaveTextContent(
      'Loading…',
    );
    answer(USER);
    expect(await screen.findByRole('button', { name: 'Delete my account' })).toBeInTheDocument();
  });

  it('Scenario: a visitor who is not signed in has no account to delete', async () => {
    mockApi({ 'GET /me': () => ANON });
    renderApp('/account');
    await settle();
    expect(screen.queryByRole('button', { name: 'Delete my account' })).not.toBeInTheDocument();
    expect(
      screen.getByText('You are not signed in, so there is no account to delete.'),
    ).toBeInTheDocument();
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Sign in' })).toHaveAttribute(
      'href',
      '/signin',
    );
  });
});

describe('Feature: what the account has left today', () => {
  it("Scenario: the page shows the name and today's AI answers and web searches from GET /me", async () => {
    mockApi({
      'GET /me': () => ({
        ...USER,
        limits: {
          guideQuestions: { limit: 30, remaining: 28 },
          webSearches: { search: { limit: 10, remaining: 8 }, guide: { limit: 3, remaining: 2 } },
          resetsAt: '2026-11-06T00:00:00Z',
        },
      }),
    });
    renderApp('/account');
    const who = await screen.findByRole('region', { name: 'Signed in' });
    expect(within(who).getByText('Ada', { selector: 'dd' })).toBeInTheDocument();
    expect(within(who).getByText('28 of 30 · resets 00:00 UTC')).toBeInTheDocument();
    expect(within(who).getByText('8 of 10 · resets 00:00 UTC')).toBeInTheDocument();
    expect(within(who).getByText('2 of 3 · resets 00:00 UTC')).toBeInTheDocument();
  });

  it('Scenario: a count the server does not give is not shown', async () => {
    mockApi({
      'GET /me': () => ({
        ...USER,
        limits: {
          guideQuestions: null,
          webSearches: { search: null, guide: null },
          resetsAt: '2026-11-06T00:00:00Z',
        },
      }),
    });
    renderApp('/account');
    const who = await screen.findByRole('region', { name: 'Signed in' });
    expect(within(who).queryByText('AI answers left today')).toBeNull();
    expect(within(who).queryByText('Web searches left today')).toBeNull();
  });
});

describe('Feature: the radar motion setting', () => {
  it('Scenario: on by default; turning it off saves it with the account and reads GET /me again', async () => {
    // Given a signed-in user whose radar moves
    let me = USER;
    const m = mockApi({
      'GET /me': () => me,
      'PATCH /me/preferences': ({ body }) => {
        me = { ...USER, preferences: body as { radarMotion: boolean } };
        return me.preferences;
      },
    });
    renderApp('/account');
    const box = await screen.findByRole('checkbox', { name: 'Radar: keep moving' });
    expect(box).toBeChecked();
    // When they turn it off
    await userEvent.click(box);
    // Then it is saved on the server and the page shows the new value from GET /me
    await waitFor(() => expect(m.callsTo('PATCH', '/me/preferences')).toHaveLength(1));
    expect(m.callsTo('PATCH', '/me/preferences')[0]!.body).toEqual({ radarMotion: false });
    await waitFor(() => expect(box).not.toBeChecked());
    expect(m.callsTo('GET', '/me').length).toBeGreaterThan(1);
  });

  it('Scenario: the setting cannot be saved, the page says so and keeps the old value', async () => {
    mockApi({
      'GET /me': () => USER,
      'PATCH /me/preferences': () => problem(503, 'Unavailable'),
    });
    renderApp('/account');
    const box = await screen.findByRole('checkbox', { name: 'Radar: keep moving' });
    await userEvent.click(box);
    expect(await screen.findByRole('alert')).toHaveTextContent('Not saved');
    expect(box).toBeChecked();
  });

  it('Scenario: a visitor who is not signed in has no setting', async () => {
    mockApi({ 'GET /me': () => ANON });
    renderApp('/account');
    await settle();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });
});
