import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ANON, USER } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { SIGN_IN_RETURN_KEY } from '@/lib/signInReturn';

const header = () => document.querySelector<HTMLElement>('header.site-header')!;

describe('Feature: end users sign in on the public site (ADR 0035)', () => {
  afterEach(() => window.sessionStorage.clear());

  it('Scenario: a visitor sees "Sign in with GitHub" in the header when the site offers it', async () => {
    mockApi({ 'GET /me': () => ANON });
    renderApp('/sources');
    const link = await within(header()).findByRole('link', { name: 'Sign in with GitHub' });
    expect(link).toHaveAttribute('href', '/oauth2/authorization/github');
  });

  it('Scenario: no sign-in is offered where the site has no provider', async () => {
    mockApi({ 'GET /me': () => ({ ...ANON, loginProviders: [] }) });
    renderApp('/sources');
    await settle();
    expect(within(header()).queryByRole('link', { name: /Sign in/ })).toBeNull();
  });

  it('Scenario: after sign-in the visitor is back on the page where they started', async () => {
    mockApi({ 'GET /me': () => ANON });
    const before = renderApp('/saved');
    const link = await within(header()).findByRole('link', { name: 'Sign in with GitHub' });
    link.addEventListener('click', (e) => e.preventDefault(), { once: true });
    await userEvent.click(link);
    expect(window.sessionStorage.getItem(SIGN_IN_RETURN_KEY)).toBe('/saved');
    await settle();
    before.unmount();

    // GitHub sends the browser back to "/" (the public port's return), now signed in.
    mockApi({ 'GET /me': () => USER });
    const { router } = renderApp('/');
    await waitFor(() => expect(router.state.location.pathname).toBe('/saved'));
    expect(window.sessionStorage.getItem(SIGN_IN_RETURN_KEY)).toBeNull();
  });

  it('Scenario: a stored return that is not a path on this site is ignored', async () => {
    window.sessionStorage.setItem(SIGN_IN_RETURN_KEY, '//evil.example/x');
    mockApi({ 'GET /me': () => USER });
    const { router } = renderApp('/');
    await settle();
    expect(router.state.location.pathname).toBe('/');
    expect(window.sessionStorage.getItem(SIGN_IN_RETURN_KEY)).toBeNull();
  });

  it('Scenario: a failed sign-in says so, and offers to try again', async () => {
    mockApi({ 'GET /me': () => ANON });
    renderApp('/?signin=failed');
    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in did not work. Please try again.');
  });

  it('Scenario: the Saved page offers sign-in to keep saved items on every device', async () => {
    mockApi({ 'GET /me': () => ANON });
    renderApp('/saved');
    const main = await screen.findByRole('main');
    expect(await within(main).findByText('Saved in this browser only. Sign in to keep them on every device.')).toBeInTheDocument();
    expect(within(main).getByRole('link', { name: 'Sign in with GitHub' })).toBeInTheDocument();
  });

  it('Scenario: signed in, Saved says the items are on the account and offers no sign-in', async () => {
    mockApi({ 'GET /me': () => USER, 'GET /me/bookmarks': () => [] });
    renderApp('/saved');
    const main = await screen.findByRole('main');
    expect(await within(main).findByText('Saved on your account.')).toBeInTheDocument();
    expect(within(main).queryByRole('link', { name: /Sign in/ })).toBeNull();
    expect(within(header()).getByRole('link', { name: 'Saved' })).toBeInTheDocument();
  });
});
