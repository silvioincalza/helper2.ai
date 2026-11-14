import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { SOURCES, STAGING_SOURCES, makeRecent } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { mockViewport } from '@/test/viewport';

const list = async (name: string) =>
  (await screen.findByRole('heading', { name, level: 2 })).closest('section') as HTMLElement;

describe('Feature: the hint under the home box fits the device', () => {
  it('Scenario: the page holds the keyboard hint and the touch hint, one after the other', async () => {
    mockApi();
    renderApp('/');
    expect(
      await screen.findByText('Enter searches. Shift+Enter asks the guide for picks with proof.'),
    ).toHaveClass('h2-hint__keys');
    expect(screen.getByText('Search, or tap Ask for picks with proof.')).toHaveClass(
      'h2-hint__touch',
    );
    await settle();
  });

  it('Scenario: with a mouse only the keyboard hint shows; on a touch screen only the touch hint, also to a screen reader', () => {
    const css = readFileSync(join(process.cwd(), 'src/features/home/home.css'), 'utf8');
    const outsideMedia = css.replace(/@media[^{]*\{(?:[^{}]*\{[^}]*\})*\s*\}/g, '');
    expect(outsideMedia).toMatch(/\.h2-hint__keys \{\s*display: none;/);
    expect(outsideMedia).not.toMatch(/\.h2-hint__touch \{/);
    expect(css).toMatch(
      /@media \(hover: hover\) and \(pointer: fine\) \{\s*\.h2-hint__keys \{\s*display: inline;\s*\}\s*\.h2-hint__touch \{\s*display: none;/,
    );
  });
});

describe('home', () => {
  it('says what the site is, with the box, the two lists and what it does, and no results', async () => {
    mockApi({ 'GET /sources': () => STAGING_SOURCES });
    renderApp('/');
    const h1 = await screen.findByRole('heading', { level: 1 });
    expect(h1).toHaveAccessibleName('helper2.ai The metasearch for the AI universe.');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(within(h1).getByText('helper2.ai')).toHaveClass('h2-hero__name');
    expect(screen.queryByText(/AI sources$/)).not.toBeInTheDocument();
    expect(await screen.findByText(/^5-10 of 13 sources at a time/)).toBeInTheDocument();
    expect(
      screen.getByText(
        /^One search across the world of AI for models, apps, MCP servers and papers/,
      ),
    ).not.toHaveTextContent(/\d/);
    expect(screen.getByText(/Enter searches\. Shift\+Enter asks the guide/)).toBeInTheDocument();
    const does = screen
      .getByRole('heading', { name: 'What helper2.ai does for you' })
      .closest('section') as HTMLElement;
    expect(
      within(does)
        .getAllByRole('heading', { level: 3 })
        .map((h) => h.textContent),
    ).toEqual(['Searches for you', 'Recommends up to three', 'Shows its proof']);
    expect(screen.getAllByRole('search')).toHaveLength(1);
    const news = await list('New in the catalog');
    await list('Coming up');
    expect(news.compareDocumentPosition(does) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByText('Acme Translator')).not.toBeInTheDocument();
    expect(screen.queryByText(/No (tracking )?ads\./)).not.toBeInTheDocument();
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
    expect(await screen.findByText(/^1 of 1 source at a time/)).toBeInTheDocument();
  });

  it('the radar shows 5 to 10 of the 13 external sources, the same ones the legend counts', async () => {
    mockApi({ 'GET /sources': () => STAGING_SOURCES });
    renderApp('/');
    await screen.findByText(/^5-10 of 13 sources at a time/);
    const radar = await screen.findByRole('list', { name: 'Some of the sources we search' });
    await waitFor(() => expect(within(radar).getAllByRole('link')).toHaveLength(5), {
      timeout: 6000,
    });
    const external = STAGING_SOURCES.filter((s) => s.external).map((s) => s.id);
    for (const dot of within(radar).getAllByRole('link'))
      expect(external).toContain(dot.dataset.source);
    const legend = screen.getByText(/sources at a time/);
    expect(legend).toHaveTextContent(
      '5-10 of 13 sources at a time · ● answering ● slow or limited ● failed · Sources page',
    );
    expect(within(legend).getByRole('link', { name: 'Sources page' })).toHaveAttribute(
      'href',
      '/sources',
    );
  });

  it('Scenario: Tab reaches the search box before the moving radar, and the page has no button that pauses motion', async () => {
    mockApi({ 'GET /sources': () => STAGING_SOURCES });
    renderApp('/');
    const radar = await screen.findByRole('list', { name: 'Some of the sources we search' });
    const box = screen.getByRole('textbox', { name: 'Search AI resources' });
    const after = (a: Element, b: Element) =>
      Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
    expect(after(box, radar)).toBe(true);
    expect(after(radar, await list('New in the catalog'))).toBe(true);
    expect(screen.queryByRole('button', { name: /motion/i })).not.toBeInTheDocument();
  });

  it('Scenario: the title folds away while the focus is on a row: the row is brought back into view, and a row scrolled off screen is left alone', async () => {
    mockApi();
    renderApp('/');
    const news = await list('New in the catalog');
    const row = (await within(news).findAllByRole('link'))[0]!;
    const hero = document.querySelector('.h2-hero__text')!;
    const folded = () => {
      const done = new Event('transitionend', { bubbles: true });
      Object.defineProperty(done, 'propertyName', { value: 'max-height' });
      hero.dispatchEvent(done);
    };
    const at = (top: number) =>
      vi.spyOn(row, 'getBoundingClientRect').mockReturnValue({ top, bottom: top + 24 } as DOMRect);
    act(() => row.focus());
    vi.mocked(Element.prototype.scrollIntoView).mockClear();
    at(90);
    folded();
    expect(vi.mocked(Element.prototype.scrollIntoView).mock.contexts).toEqual([row]);
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' });
    at(-900);
    folded();
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1);
  });

  it('Scenario: at the bottom of a short page the bar sticks, the page loses the folded height and the scroll drops by it: the bar stays stuck until the visitor scrolls up past the fold', async () => {
    mockViewport(1280);
    mockApi();
    renderApp('/');
    await list('New in the catalog');
    const bar = document.querySelector<HTMLElement>('.h2-pinned')!;
    const more = document.querySelector<HTMLElement>('.h2-pinned__more')!;
    vi.spyOn(more, 'getBoundingClientRect').mockReturnValue({ height: 120 } as DOMRect);
    const scrollTo = (y: number, barTop: number) => {
      vi.spyOn(bar, 'getBoundingClientRect').mockReturnValue({ top: barTop } as DOMRect);
      act(() => {
        Object.defineProperty(window, 'scrollY', { configurable: true, value: y });
        window.dispatchEvent(new Event('scroll'));
      });
    };
    scrollTo(300, 0);
    expect(bar).toHaveClass('is-stuck');
    scrollTo(200, 100);
    expect(bar).toHaveClass('is-stuck');
    scrollTo(179, 121);
    expect(bar).not.toHaveClass('is-stuck');
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 });
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

  it('the header links to the News and Events pages; the two lists keep their ids', async () => {
    mockApi();
    renderApp('/');
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'News' })).toHaveAttribute('href', '/news');
    expect(within(nav).getByRole('link', { name: 'Events' })).toHaveAttribute('href', '/events');
    expect((await list('New in the catalog')).id).toBe('news');
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
