import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ANON, USER } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { SIGN_IN_RETURN_KEY } from '@/lib/signInReturn';

const header = () => document.querySelector<HTMLElement>('header.site-header')!;

describe('Feature: end users sign in on the public site (ADR 0024)', () => {
  afterEach(() => window.sessionStorage.clear());

  it('Scenario: "Sign in" in the header opens the sign-in page, which offers the site\'s providers', async () => {
    mockApi({ 'GET /me': () => ANON });
    const { router } = renderApp('/sources');
    // The sources page loads on demand: the header comes with it.
    await waitFor(() => expect(header()).not.toBeNull());
    await userEvent.click(await within(header()).findByRole('link', { name: 'Sign in' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/signin'));
    const main = await screen.findByRole('main');
    expect(
      await within(main).findByRole('heading', {
        level: 1,
        name: 'Sign in to keep what you find.',
      }),
    ).toBeInTheDocument();
    expect(within(main).getByRole('link', { name: 'Continue with GitHub' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/github',
    );
  });

  it('Scenario: a provider the site has not set up is not offered', async () => {
    mockApi({
      'GET /me': () => ({
        ...ANON,
        loginProviders: [
          { id: 'github', name: 'GitHub' },
          { id: 'google', name: 'Google' },
        ],
      }),
    });
    renderApp('/signin');
    const main = await screen.findByRole('main');
    await within(main).findByRole('link', { name: 'Continue with GitHub' });
    expect(within(main).getByRole('link', { name: 'Continue with Google' })).toBeInTheDocument();
    expect(within(main).getAllByRole('link', { name: /^Continue with/ })).toHaveLength(2);
  });

  it('Scenario: on a site with no provider, the sign-in page says so and offers none', async () => {
    mockApi({ 'GET /me': () => ({ ...ANON, loginProviders: [] }) });
    renderApp('/signin');
    const main = await screen.findByRole('main');
    expect(
      await within(main).findByRole('heading', { name: 'Sign-in is not offered here' }),
    ).toBeInTheDocument();
    expect(within(main).queryByRole('link', { name: /^Continue with/ })).toBeNull();
  });

  it('Scenario: a signed-in user on the sign-in page is pointed to the account', async () => {
    mockApi({ 'GET /me': () => USER });
    renderApp('/signin');
    const main = await screen.findByRole('main');
    expect(await within(main).findByText('Signed in as Ada.')).toBeInTheDocument();
    expect(within(main).getByRole('link', { name: 'Your account' })).toHaveAttribute(
      'href',
      '/account',
    );
  });

  it('Scenario: no sign-in is offered where the site has no provider', async () => {
    mockApi({ 'GET /me': () => ({ ...ANON, loginProviders: [] }) });
    renderApp('/sources');
    await waitFor(() => expect(header()).not.toBeNull());
    await settle();
    expect(within(header()).queryByRole('link', { name: /Sign in/ })).toBeNull();
  });

  it('Scenario: after sign-in the visitor is back on the page where they started', async () => {
    mockApi({ 'GET /me': () => ANON });
    const before = renderApp('/saved');
    // The saved page loads on demand: the header comes with it.
    await waitFor(() => expect(header()).not.toBeNull());
    await userEvent.click(await within(header()).findByRole('link', { name: 'Sign in' }));
    const main = await screen.findByRole('main');
    const link = await within(main).findByRole('link', { name: 'Continue with GitHub' });
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

  it('Scenario: a retry after a failed sign-in does not bring the failure back', async () => {
    mockApi({ 'GET /me': () => ANON });
    renderApp('/saved?signin=failed&x=1');
    // The saved page loads on demand: the header comes with it.
    await waitFor(() => expect(header()).not.toBeNull());
    await userEvent.click(await within(header()).findByRole('link', { name: 'Sign in' }));
    const main = await screen.findByRole('main');
    const link = await within(main).findByRole('link', { name: 'Continue with GitHub' });
    link.addEventListener('click', (e) => e.preventDefault(), { once: true });
    await userEvent.click(link);
    // Only the page and its own parameters: the marker of the earlier attempt is not kept.
    expect(window.sessionStorage.getItem(SIGN_IN_RETURN_KEY)).toBe('/saved?x=1');
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
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Sign-in did not work. Please try again.');
    // The link comes with GET /me: only a site with a provider offers it.
    expect(
      await within(alert).findByRole('link', { name: 'Choose how to sign in' }),
    ).toHaveAttribute('href', '/signin');
  });

  it('Scenario: the Saved page offers sign-in to keep saved items on every device', async () => {
    mockApi({ 'GET /me': () => ANON });
    renderApp('/saved');
    const main = await screen.findByRole('main');
    expect(
      await within(main).findByText(
        'Saved in this browser only. Sign in to keep them on every device.',
      ),
    ).toBeInTheDocument();
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
