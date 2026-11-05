import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { SOURCES, STAGING_SOURCES, makeRecent } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const list = async (name: string) =>
  (await screen.findByRole('heading', { name, level: 2 })).closest('section') as HTMLElement;

describe('home (design v4)', () => {
  it('says what the site is, with the box, the two lists and what it does, and no results', async () => {
    mockApi({ 'GET /sources': () => STAGING_SOURCES });
    renderApp('/');
    // One h1 that names the site: "helper2.ai" on its own line, then what it is.
    const h1 = await screen.findByRole('heading', { level: 1 });
    expect(h1).toHaveAccessibleName('helper2.ai The metasearch for the AI universe.');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(within(h1).getByText('helper2.ai')).toHaveClass('v4-hero__name');
    // No line above the title; the number of sources is in the radar legend, from GET /sources.
    expect(screen.queryByText(/AI sources$/)).not.toBeInTheDocument();
    expect(await screen.findByText(/^3-5 of 13 sources at a time/)).toBeInTheDocument();
    expect(screen.getByText(/^One search across the world of AI/)).not.toHaveTextContent(/\d/);
    // The 4-word rule comes from the code that applies it.
    expect(
      screen.getByText(/Enter searches\. For picks with a quote that proves them/),
    ).toBeInTheDocument();
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

  it('the legend counts only enabled external sources, and shows no count while GET /sources has not answered', async () => {
    mockApi({
      'GET /sources': () => [
        ...SOURCES,
        { ...SOURCES[0]!, id: 'off', name: 'Off', enabled: false },
      ],
    });
    renderApp('/');
    expect(screen.queryByText(/at a time/)).not.toBeInTheDocument();
    // GitHub counts; Helper2's own seed and the disabled source do not.
    expect(await screen.findByText(/^1 of 1 source at a time/)).toBeInTheDocument();
  });

  it('the radar shows 3 to 5 of the 13 external sources, the same ones the legend counts', async () => {
    mockApi({ 'GET /sources': () => STAGING_SOURCES });
    renderApp('/');
    await screen.findByText(/^3-5 of 13 sources at a time/);
    const radar = await screen.findByRole('list', { name: 'Some of the sources we search' });
    // The first 3 come in one after another, 0.6 s apart.
    await waitFor(() => expect(within(radar).getAllByRole('link')).toHaveLength(3), {
      timeout: 4000,
    });
    const external = STAGING_SOURCES.filter((s) => s.external).map((s) => s.id);
    for (const dot of within(radar).getAllByRole('link'))
      expect(external).toContain(dot.dataset.source);
    // The legend: how many of how many, the three colours, and the Sources page.
    const legend = screen.getByText(/sources at a time/);
    expect(legend).toHaveTextContent(
      '3-5 of 13 sources at a time · ● answering ● slow or limited ● failed · Sources page',
    );
    expect(within(legend).getByRole('link', { name: 'Sources page' })).toHaveAttribute(
      'href',
      '/sources',
    );
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
    // The row's title links to the resource page; there is no preview.
    expect(await screen.findByRole('link', { name: 'Acme Translator' })).toHaveAttribute(
      'href',
      expect.stringMatching(/^\/resource\//),
    );
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
    await userEvent.click(await screen.findByRole('button', { name: 'Postgres MCP server' }));
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

  it('/?q=… still shows results, marked noindex, follow like /search, with no canonical link', async () => {
    mockApi();
    renderApp('/?q=mcp');
    // The row's title links to the resource page; there is no preview.
    expect(await screen.findByRole('link', { name: 'Acme Translator' })).toHaveAttribute(
      'href',
      expect.stringMatching(/^\/resource\//),
    );
    expect(document.querySelector('link[rel="canonical"]')).toBeNull();
    expect(document.head.querySelector('meta[name="robots"]')).toHaveAttribute(
      'content',
      'noindex, follow',
    );
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
