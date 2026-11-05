import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { SOURCES, makeRecent } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const list = async (name: string) =>
  (await screen.findByRole('heading', { name, level: 2 })).closest('section') as HTMLElement;

describe('home (design v4)', () => {
  it('says what the site is, with the box, the two lists and what it does, and no results', async () => {
    mockApi();
    renderApp('/');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      'The metasearch for the AI ecosystem.',
    );
    // The number of sources is the enabled sources of GET /sources, never a number in the copy.
    expect(await screen.findByText('One search · 2 AI sources')).toBeInTheDocument();
    expect(screen.getByText(/^Models, apps, agents/)).not.toHaveTextContent(/\d/);
    // The 4-word rule comes from the code that applies it.
    expect(screen.getByText(/A question of 4 words or more/)).toBeInTheDocument();
    const does = screen
      .getByRole('heading', { name: 'What helper2 does for you' })
      .closest('section') as HTMLElement;
    expect(
      within(does)
        .getAllByRole('heading', { level: 3 })
        .map((h) => h.textContent),
    ).toEqual(['Searches for you', 'Recommends up to three', 'Shows its proof']);
    expect(screen.getAllByRole('search')).toHaveLength(1);
    const news = await list('New this week');
    await list('Coming up');
    expect(news.compareDocumentPosition(does) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByText('Acme Translator')).not.toBeInTheDocument();
    expect(screen.getByText(/No ads\./)).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toHaveTextContent('Switzerland · EU servers');
  });

  it('counts only enabled sources, and says no count while GET /sources has not answered', async () => {
    mockApi({
      'GET /sources': () => [
        ...SOURCES,
        { ...SOURCES[0]!, id: 'off', name: 'Off', enabled: false },
      ],
    });
    renderApp('/');
    expect(screen.queryByText(/AI sources/)).not.toBeInTheDocument();
    expect(await screen.findByText('One search · 2 AI sources')).toBeInTheDocument();
  });

  it('draws one radar dot per enabled source, hidden from screen readers', async () => {
    mockApi();
    const { container } = renderApp('/');
    await screen.findByText('One search · 2 AI sources');
    const radar = container.querySelector('svg.v4-radar') as SVGElement;
    expect(radar).toHaveAttribute('aria-hidden', 'true');
    expect(radar.querySelectorAll('.v4-dot')).toHaveLength(2);
    expect(radar.querySelector('.v4-dot--live')).toHaveTextContent('GitHub');
    expect(radar.querySelector('.v4-dot--idle')).toHaveTextContent('Helper2 seed');
  });

  it('Enter in the box opens the results on /search', async () => {
    const m = mockApi();
    const { router, params } = renderApp('/');
    await userEvent.type(
      screen.getByRole('textbox', { name: 'Search AI resources' }),
      'mcp github{Enter}',
    );
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(params().get('q')).toBe('mcp github');
    expect(await screen.findByRole('link', { name: 'Acme Translator' })).toBeInTheDocument();
    expect(m.callsTo('GET', '/search')).toHaveLength(1);
  });

  it('typing on the home page does not move to /search before Enter', async () => {
    mockApi();
    const { router } = renderApp('/');
    await userEvent.type(
      screen.getByRole('textbox', { name: 'Search AI resources' }),
      'rag library',
    );
    await act(async () => {
      await new Promise((r) => setTimeout(r, 900));
    });
    expect(router.state.location.pathname).toBe('/');
  });

  it('an example opens /search with its words and its audience path', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0);
    mockApi();
    const { router, params } = renderApp('/');
    await userEvent.click(screen.getByRole('button', { name: 'Postgres MCP server' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(params().get('q')).toBe('postgres mcp server');
    expect(params().get('path')).toBe('BUILD_WITH_AI');
    expect(params().get('ask')).toBeNull();
    await settle();
    vi.restoreAllMocks();
  });

  it('the header links to the two lists', async () => {
    mockApi();
    renderApp('/');
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'News' })).toHaveAttribute('href', '/#news');
    expect(within(nav).getByRole('link', { name: 'Events' })).toHaveAttribute('href', '/#events');
    expect((await list('New this week')).id).toBe('news');
    expect((await list('Coming up')).id).toBe('events');
  });

  it('/#events scrolls to the Coming up list', async () => {
    mockApi();
    renderApp('/#events');
    const events = await list('Coming up');
    await waitFor(() => expect(Element.prototype.scrollIntoView).toHaveBeenCalled());
    expect(vi.mocked(Element.prototype.scrollIntoView).mock.contexts[0]).toBe(events);
  });

  it('/?q=… still shows results and names /search as the canonical page', async () => {
    mockApi();
    renderApp('/?q=mcp');
    expect(await screen.findByRole('link', { name: 'Acme Translator' })).toBeInTheDocument();
    const canonical =
      document.head.querySelector('link[rel="canonical"]') ??
      document.querySelector('link[rel="canonical"]');
    expect(canonical).toHaveAttribute('href', '/search?q=mcp');
  });

  it('/search with no query goes home', async () => {
    mockApi();
    const { router } = renderApp('/search');
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    await settle();
  });

  it('has no axe violations', async () => {
    mockApi({ 'GET /recent': () => makeRecent() });
    const { container } = renderApp('/');
    await screen.findByRole('link', { name: 'Fresh MCP server' });
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });
});
