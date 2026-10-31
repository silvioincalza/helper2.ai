import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN, ANON, USER } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { THEME_KEY } from '@/lib/theme';
import pkg from '../../package.json';

describe('layout', () => {
  it('hides the Admin nav item and sign-in for anonymous users', async () => {
    const m = mockApi({ 'GET /me': () => ANON });
    renderApp('/');
    await waitFor(() => expect(m.callsTo('GET', '/me')).toHaveLength(1));
    expect(within(screen.getByRole('banner')).queryByRole('link', { name: /Sign in/ })).not.toBeInTheDocument();
    // Saved is on the roadmap (lib/features.ts): the landing's menu has Sources and Mission only.
    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect(within(nav).getAllByRole('link').map((a) => a.textContent)).toEqual(['Sources', 'Mission']);
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Saved' })).not.toBeInTheDocument();
  });

  it('the removed models page is not a route any more', async () => {
    mockApi();
    renderApp('/models');
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
  });

  it('hides Admin for signed-in non-admins and shows it for admins', async () => {
    mockApi({ 'GET /me': () => USER });
    const first = renderApp('/');
    await screen.findByRole('button', { name: 'Sign out' });
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
    first.unmount();
    mockApi({ 'GET /me': () => ADMIN });
    renderApp('/');
    expect(await screen.findByRole('link', { name: 'Admin' })).toBeInTheDocument();
  });

  it('has skip link, landmarks and the one-line footer', async () => {
    mockApi();
    renderApp('/');
    await settle();
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute('href', '#main');
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
    const footer = screen.getByRole('contentinfo');
    expect(footer).toHaveTextContent('helper2.ai · Switzerland');
    expect(footer).toHaveTextContent('No ads.');
    for (const [name, href] of [['Mission', '/mission'], ['Sources', '/sources'], ['Privacy', '/privacy'], ['Terms', '/terms']] as const) {
      expect(within(footer).getByRole('link', { name })).toHaveAttribute('href', href);
    }
  });

  it('footer shows the release version and links to its notes in a new tab', async () => {
    mockApi();
    renderApp('/');
    await settle();
    const footer = screen.getByRole('contentinfo');
    const version = within(footer).getByRole('link', { name: /Release notes for version/ });
    expect(version).toHaveTextContent(`v${pkg.version}`);
    expect(version).toHaveAttribute('href', `https://github.com/silvioincalza/helper2.ai/releases/tag/v${pkg.version}`);
    expect(version).toHaveAttribute('target', '_blank');
  });

  it('footer links to the source code in a new tab', async () => {
    mockApi();
    renderApp('/');
    await settle();
    const repo = within(screen.getByRole('contentinfo')).getByRole('link', { name: /GitHub/ });
    expect(repo).toHaveAttribute('href', 'https://github.com/silvioincalza/helper2.ai');
    expect(repo).toHaveAccessibleName('Helper2.ai source code on GitHub (opens in a new tab)');
    expect(repo).toHaveAttribute('target', '_blank');
    expect(repo).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('the landing header is a row of links; every other page has the logo and the one box', async () => {
    mockApi();
    const { router } = renderApp('/');
    await settle();
    const banner = screen.getByRole('banner');
    expect(within(banner).queryByRole('searchbox')).not.toBeInTheDocument();
    expect(within(banner).queryByRole('link', { name: 'Helper2.ai home' })).not.toBeInTheDocument();
    await act(() => router.navigate('/mission'));
    const next = screen.getByRole('banner');
    expect(within(next).getByRole('link', { name: 'Helper2.ai home' })).toHaveAttribute('href', '/');
    expect(within(next).getByRole('searchbox')).toBeInTheDocument();
    await settle();
  });

  it('the theme button cycles follow system, light and dark, and remembers a fixed choice', async () => {
    mockApi();
    document.documentElement.dataset.theme = 'light';
    renderApp('/');
    await userEvent.click(screen.getByRole('button', { name: 'Theme: follow system. Change to light' }));
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(window.localStorage.getItem(THEME_KEY)).toBe('light');
    await userEvent.click(screen.getByRole('button', { name: 'Theme: light. Change to dark' }));
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(window.localStorage.getItem(THEME_KEY)).toBe('dark');
    await userEvent.click(screen.getByRole('button', { name: 'Theme: dark. Change to follow system' }));
    expect(window.localStorage.getItem(THEME_KEY)).toBeNull();
    expect(document.documentElement.dataset.theme).toBe('light'); // jsdom's device setting
    expect(screen.getByRole('button', { name: 'Theme: follow system. Change to light' })).toBeInTheDocument();
    delete document.documentElement.dataset.theme;
  });

  it('the theme button starts from the saved choice', async () => {
    mockApi();
    window.localStorage.setItem(THEME_KEY, 'dark');
    renderApp('/');
    await settle();
    expect(screen.getByRole('button', { name: 'Theme: dark. Change to follow system' })).toBeInTheDocument();
  });

  it('moving to another page focuses its heading and announces it; new search params do not move focus', async () => {
    mockApi();
    const { router } = renderApp('/');
    await userEvent.click(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Sources' }));
    const h1 = await screen.findByRole('heading', { level: 1, name: 'Sources' });
    await waitFor(() => expect(h1).toHaveFocus());
    expect(document.querySelector('[aria-live="polite"].sr-only')).toHaveTextContent('Sources');

    await act(() => router.navigate('/?q=rag'));
    await screen.findByText('Acme Translator');
    await userEvent.type(screen.getByRole('searchbox'), ' db{Enter}');
    await waitFor(() => expect(router.state.location.search).toBe('?q=rag+db'));
    expect(screen.getByRole('searchbox')).toHaveFocus();
    await settle();
  });
});
