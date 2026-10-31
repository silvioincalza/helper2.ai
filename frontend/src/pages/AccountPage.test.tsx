import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { ANON, USER } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

describe('Feature: delete my account', () => {
  it('Scenario: a signed-in user opens their account from their name in the header', async () => {
    // Given a signed-in user on the home page
    mockApi({ 'GET /me': () => USER });
    const { router, container } = renderApp('/');
    // When they follow their name in the header
    await userEvent.click(await within(screen.getByRole('banner')).findByRole('link', { name: 'Ada' }));
    // Then their account page opens with focus on its heading, and says who they are signed in as
    expect(router.state.location.pathname).toBe('/account');
    expect(await screen.findByRole('heading', { level: 1, name: 'Your account' })).toHaveFocus();
    expect(screen.getByText('Signed in with GitHub as Ada.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'What we keep and for how long' })).toHaveAttribute('href', '/privacy');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('Scenario: the user confirms by keyboard, the account is deleted and the page starts over', async () => {
    // Given a signed-in user on their account page
    const reload = vi.fn();
    vi.stubGlobal('location', { ...window.location, reload });
    const m = mockApi({ 'GET /me': () => USER, 'DELETE /me': () => new Response(null, { status: 204 }) });
    const { container } = renderApp('/account');
    // When they ask to delete the account
    (await screen.findByRole('button', { name: 'Delete my account' })).focus();
    await userEvent.keyboard('{Enter}');
    // Then a dialog asks them to confirm, with focus on Cancel, and nothing is deleted yet
    const dialog = await screen.findByRole('alertdialog', { name: 'Delete your account?' });
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus();
    expect(m.callsTo('DELETE', '/me')).toHaveLength(0);
    expect(await axeViolations(container)).toEqual([]);
    // When they move to Delete account and press it
    await userEvent.tab();
    expect(within(dialog).getByRole('button', { name: 'Delete account' })).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    // Then the account is deleted and the page reloads signed out
    await waitFor(() => expect(m.callsTo('DELETE', '/me')).toHaveLength(1));
    await waitFor(() => expect(reload).toHaveBeenCalled());
  });

  it('Scenario: the user cancels; nothing is deleted and focus returns to the button', async () => {
    const m = mockApi({ 'GET /me': () => USER });
    renderApp('/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(m.callsTo('DELETE', '/me')).toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Delete my account' })).toHaveFocus();
  });

  it('Scenario: Escape closes the dialog and focus returns to the button', async () => {
    mockApi({ 'GET /me': () => USER });
    renderApp('/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    await screen.findByRole('alertdialog');
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete my account' })).toHaveFocus();
  });

  it('Scenario: the server refuses; the user is told and stays signed in', async () => {
    const reload = vi.fn();
    vi.stubGlobal('location', { ...window.location, reload });
    mockApi({ 'GET /me': () => USER, 'DELETE /me': () => problem(503, 'Service Unavailable') });
    renderApp('/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Delete account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Your account could not be deleted');
    expect(reload).not.toHaveBeenCalled();
  });

  it('Scenario: while the page asks who is signed in, it says it is loading', async () => {
    let answer: (v: unknown) => void = () => {};
    mockApi({ 'GET /me': () => new Promise((r) => { answer = r; }) });
    renderApp('/account');
    expect(within(screen.getByRole('main')).getByRole('status')).toHaveTextContent('Loading…');
    answer(USER);
    expect(await screen.findByRole('button', { name: 'Delete my account' })).toBeInTheDocument();
  });

  it('Scenario: a visitor who is not signed in has no account to delete', async () => {
    mockApi({ 'GET /me': () => ANON });
    const { container } = renderApp('/account');
    await settle();
    expect(screen.getByRole('heading', { level: 1, name: 'Your account' })).toBeInTheDocument();
    expect(screen.getByText('You are not signed in, so there is no account to delete.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'What we keep and for how long' })).toHaveAttribute('href', '/privacy');
    expect(screen.queryByRole('button', { name: 'Delete my account' })).not.toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });
});
