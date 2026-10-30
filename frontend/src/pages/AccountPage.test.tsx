import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ANON, USER } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

describe('Feature: delete my account', () => {
  it('Scenario: a signed-in user opens their account from their name in the header', async () => {
    mockApi({ 'GET /me': () => USER });
    const { router } = renderApp('/');
    await userEvent.click(await within(screen.getByRole('banner')).findByRole('link', { name: 'Ada' }));
    expect(router.state.location.pathname).toBe('/account');
    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
  });

  it('Scenario: the user confirms, the account is deleted and the page starts over', async () => {
    // Given a signed-in user on their account page
    const reload = vi.fn();
    vi.stubGlobal('location', { ...window.location, reload });
    const m = mockApi({ 'GET /me': () => USER, 'DELETE /me': () => new Response(null, { status: 204 }) });
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
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(m.callsTo('DELETE', '/me')).toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Delete my account' })).toHaveFocus();
  });

  it('Scenario: Escape closes the dialog, which opened on Cancel', async () => {
    mockApi({ 'GET /me': () => USER });
    renderApp('/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    expect(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('Scenario: the server refuses and the user is told', async () => {
    const reload = vi.fn();
    vi.stubGlobal('location', { ...window.location, reload });
    mockApi({ 'GET /me': () => USER, 'DELETE /me': () => problem(503, 'Service Unavailable') });
    renderApp('/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Delete account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Your account could not be deleted');
    expect(reload).not.toHaveBeenCalled();
  });

  it('Scenario: a visitor who is not signed in has no account to delete', async () => {
    mockApi({ 'GET /me': () => ANON });
    renderApp('/account');
    await settle();
    expect(screen.queryByRole('button', { name: 'Delete my account' })).not.toBeInTheDocument();
    expect(screen.getByText('You are not signed in, so there is no account to delete.')).toBeInTheDocument();
  });
});
