import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN, ANON, USER } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { THEME_KEY } from '@/lib/theme';
import pkg from '../../package.json';

describe('layout', () => {
  it('offers sign-in to anonymous users and hides the Admin nav item', async () => {
    const m = mockApi({ 'GET /me': () => ANON });
    renderApp('/');
    await waitFor(() => expect(m.callsTo('GET', '/me')).toHaveLength(1));
    const header = document.querySelector<HTMLElement>('header.site-header')!;
    expect(
      await within(header).findByRole('link', { name: 'Sign in with GitHub' }),
    ).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect(
      within(nav)
        .getAllByRole('link')
        .map((a) => a.textContent),
    ).toEqual(['News', 'Events', 'Saved']);
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
  });

  it('on a small screen the menu button opens and closes the pages and sign-in; Escape and a new page close it', async () => {
    mockApi({ 'GET /me': () => ANON });
    const user = userEvent.setup();
    renderApp('/');
    const header = document.querySelector<HTMLElement>('header.site-header')!;
    const button = within(header).getByRole('button', { name: 'Menu' });
    const panel = document.getElementById('site-menu')!;
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveAttribute('aria-controls', 'site-menu');
    expect(panel).not.toHaveClass('is-open');
    // The panel holds the pages and the sign-in.
    expect(within(panel).getByRole('navigation', { name: 'Main' })).toBeInTheDocument();
    expect(
      await within(panel).findByRole('link', { name: 'Sign in with GitHub' }),
    ).toBeInTheDocument();

    await user.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(panel).toHaveClass('is-open');
    await user.keyboard('{Escape}');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveFocus();

    // A link to a list on the home page keeps the pathname; it closes the menu anyway.
    await user.click(button);
    await user.click(within(panel).getByRole('link', { name: 'News' }));
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(panel).not.toHaveClass('is-open');

    await user.click(button);
    await user.click(within(panel).getByRole('link', { name: 'Saved' }));
    await screen.findByRole('heading', { level: 1, name: 'Saved resources' });
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(panel).not.toHaveClass('is-open');
  });

  it('the removed models page is not a route any more', async () => {
    mockApi();
    renderApp('/models');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument();
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

  it('has skip link, landmarks and the coverage disclaimer', async () => {
    mockApi();
    renderApp('/');
    await settle();
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute(
      'href',
      '#main',
    );
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toHaveTextContent(
      'searches the sources listed on the Sources page, not the whole web',
    );
  });

  it('footer shows the release version and links to its notes in a new tab', async () => {
    mockApi();
    renderApp('/');
    await settle();
    const footer = screen.getByRole('contentinfo');
    const version = within(footer).getByRole('link', { name: /Release notes for version/ });
    expect(version).toHaveTextContent(`v${pkg.version}`);
    expect(version).toHaveAttribute(
      'href',
      `https://github.com/silvioincalza/helper2.ai/releases/tag/v${pkg.version}`,
    );
    expect(version).toHaveAttribute('target', '_blank');
  });

  it('footer shows the copyright year and links to the repo and license in a new tab', async () => {
    mockApi();
    renderApp('/');
    await settle();
    const footer = screen.getByRole('contentinfo');
    expect(footer).toHaveTextContent(`© ${new Date().getFullYear()} Silvio Incalza`);
    const repo = within(footer).getByRole('link', { name: /GitHub/ });
    expect(repo).toHaveAttribute('href', 'https://github.com/silvioincalza/helper2.ai');
    expect(repo).toHaveAccessibleName('Helper2.ai source code on GitHub (opens in a new tab)');
    const license = within(footer).getByRole('link', { name: /Apache-2.0/ });
    expect(license).toHaveAttribute(
      'href',
      'https://github.com/silvioincalza/helper2.ai/blob/main/LICENSE',
    );
    for (const link of [repo, license]) {
      expect(link).toHaveAttribute('target', '_blank');
      expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    }
  });

  it('the theme is light by default; the button switches light and dark and remembers the choice', async () => {
    mockApi();
    document.documentElement.dataset.theme = 'light';
    renderApp('/');
    await userEvent.click(screen.getByRole('button', { name: 'Theme: light. Change to dark' }));
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(window.localStorage.getItem(THEME_KEY)).toBe('dark');
    await userEvent.click(screen.getByRole('button', { name: 'Theme: dark. Change to light' }));
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(window.localStorage.getItem(THEME_KEY)).toBe('light');
    delete document.documentElement.dataset.theme;
  });

  it('the theme button starts from the theme public/theme.js applied before paint', async () => {
    mockApi();
    window.localStorage.setItem(THEME_KEY, 'dark');
    document.documentElement.dataset.theme = 'dark';
    renderApp('/');
    await settle();
    expect(
      screen.getByRole('button', { name: 'Theme: dark. Change to light' }),
    ).toBeInTheDocument();
    delete document.documentElement.dataset.theme;
  });

  describe('when storage is blocked', () => {
    afterEach(() => {
      vi.restoreAllMocks();
      delete document.documentElement.dataset.theme;
    });

    it('storage can be read but not written: the button and the page agree after each click', async () => {
      mockApi();
      document.documentElement.dataset.theme = 'light';
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new DOMException('full', 'QuotaExceededError');
      });
      renderApp('/');
      await userEvent.click(screen.getByRole('button', { name: 'Theme: light. Change to dark' }));
      expect(document.documentElement.dataset.theme).toBe('dark');
      expect(
        screen.getByRole('button', { name: 'Theme: dark. Change to light' }),
      ).toBeInTheDocument();
      await userEvent.click(screen.getByRole('button', { name: 'Theme: dark. Change to light' }));
      expect(document.documentElement.dataset.theme).toBe('light');
      expect(
        screen.getByRole('button', { name: 'Theme: light. Change to dark' }),
      ).toBeInTheDocument();
    });

    it('storage can be neither read nor written: the page starts light and switching works', async () => {
      mockApi();
      document.documentElement.dataset.theme = 'light';
      const blocked = () => {
        throw new DOMException('blocked', 'SecurityError');
      };
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked);
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked);
      renderApp('/');
      await userEvent.click(screen.getByRole('button', { name: 'Theme: light. Change to dark' }));
      expect(document.documentElement.dataset.theme).toBe('dark');
      await userEvent.click(screen.getByRole('button', { name: 'Theme: dark. Change to light' }));
      expect(document.documentElement.dataset.theme).toBe('light');
    });
  });

  it('moving to another page focuses its heading and announces it; new search params do not move focus', async () => {
    mockApi();
    const { router } = renderApp('/');
    // The footer names Sources twice (the coverage line and the page list): either opens the page.
    await userEvent.click(
      within(screen.getByRole('contentinfo')).getAllByRole('link', { name: 'Sources' })[0]!,
    );
    const h1 = await screen.findByRole('heading', { level: 1, name: 'Sources' });
    await waitFor(() => expect(h1).toHaveFocus());
    expect(document.querySelector('[aria-live="polite"].sr-only')).toHaveTextContent('Sources');

    await act(() => router.navigate('/search?q=rag'));
    await screen.findByRole('list', { name: 'Search results' });
    await userEvent.type(
      screen.getByRole('textbox', { name: 'Search AI resources' }),
      ' db{Enter}',
    );
    await waitFor(() => expect(router.state.location.search).toBe('?q=rag+db'));
    expect(screen.getByRole('textbox', { name: 'Search AI resources' })).toHaveFocus();
    await settle();
  });
});
