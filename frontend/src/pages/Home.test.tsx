import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ResultCard } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { makeCard, makeRecent } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const day = (value: string) => ({
  value,
  precision: 'DAY' as const,
  status: 'KNOWN' as const,
  sourceId: 'github',
  alternatives: [],
});

function news(n: number): ResultCard[] {
  return Array.from({ length: n }, (_, i) => {
    const base = makeCard();
    return makeCard({
      id: `44444444-4444-5444-8444-${String(i).padStart(12, '0')}`,
      title: `News item ${i}`,
      type: 'MCP_SERVER',
      timeline: { ...base.timeline, published: day('2026-10-26T00:00:00Z') },
    });
  });
}

function event(i: number, start: string): ResultCard {
  const base = makeCard();
  return makeCard({
    id: `55555555-5555-5555-8555-${String(i).padStart(12, '0')}`,
    title: `Event ${i}`,
    type: 'EVENT',
    location: 'Zurich',
    eventKind: 'CONFERENCE',
    timeline: { ...base.timeline, eventStart: day(start), eventEnd: null },
  });
}

const board = async (name: string) =>
  (await screen.findByRole('heading', { name, level: 2 })).closest('section') as HTMLElement;

describe('home: only the home page', () => {
  it('says what the site is for, with the box, what it does and the two lists, and no results', async () => {
    mockApi();
    renderApp('/');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      'Find the right AI tool for your task.',
    );
    // The number of sources comes from the registry, never a fixed number in the text.
    expect(await screen.findByText(/from 2 sources at once/)).toBeInTheDocument();
    const does = screen
      .getByRole('heading', { name: 'What helper2 does for you' })
      .closest('section') as HTMLElement;
    expect(
      within(does)
        .getAllByRole('heading', { level: 3 })
        .map((h) => h.textContent),
    ).toEqual(['Searches for you', 'Recommends up to three', 'Shows its proof']);
    expect(screen.getAllByRole('search')).toHaveLength(1);
    // What is new comes before what helper2 does: the lists are what the page is about.
    const newList = await board('New this week');
    await board('Coming up');
    expect(newList.compareDocumentPosition(does) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByText('Acme Translator')).not.toBeInTheDocument();
    expect(screen.getByText(/No ads\./)).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toHaveTextContent('Switzerland · EU servers');
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
    // The row's title and the preview's title: both link to the resource page.
    expect(await screen.findAllByRole('link', { name: 'Acme Translator' })).toHaveLength(2);
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

  it('a list shows its first six rows and opens the rest in place', async () => {
    mockApi({ 'GET /recent': () => makeRecent({ items: news(8) }) });
    renderApp('/');
    const section = await board('New this week');
    await within(section).findByRole('link', { name: 'News item 0' });
    expect(within(section).getAllByRole('listitem')).toHaveLength(6);
    expect(within(section).getAllByText('Published')[0]).toBeInTheDocument();
    const more = within(section).getByRole('button', { name: 'Show all 8' });
    expect(more).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(more);
    expect(within(section).getAllByRole('listitem')).toHaveLength(8);
    expect(within(section).getByRole('button', { name: 'Show fewer' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  it('a list that fits shows no button, and nothing moves on its own', async () => {
    mockApi({ 'GET /recent': () => makeRecent({ items: news(6) }) });
    renderApp('/');
    const section = await board('New this week');
    await within(section).findByRole('link', { name: 'News item 5' });
    expect(within(section).queryByRole('button', { name: /Show all/ })).not.toBeInTheDocument();
    expect(section.querySelector('ul[aria-hidden="true"]')).toBeNull();
  });

  it('a news row shows the source’s own line, but not an AI-written summary', async () => {
    const [own, ai] = news(2);
    mockApi({
      'GET /recent': () =>
        makeRecent({
          items: [
            {
              ...own!,
              description: 'Connects an agent to Slack.',
              descriptionOrigin: 'SOURCE_PROVIDED',
            },
            { ...ai!, description: 'A summary a model wrote.', descriptionOrigin: 'AI_SUMMARY' },
          ],
        }),
    });
    renderApp('/');
    const section = await board('New this week');
    expect(await within(section).findByText('Connects an agent to Slack.')).toBeInTheDocument();
    expect(within(section).queryByText('A summary a model wrote.')).not.toBeInTheDocument();
  });

  it('the header links to the News and Events lists', async () => {
    mockApi();
    renderApp('/');
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'News' })).toHaveAttribute('href', '/#news');
    expect(within(nav).getByRole('link', { name: 'Events' })).toHaveAttribute('href', '/#events');
    expect((await board('New this week')).id).toBe('news');
    expect((await board('Coming up')).id).toBe('events');
  });

  it('/#events scrolls to the Events list', async () => {
    mockApi();
    renderApp('/#events');
    const events = await board('Coming up');
    await waitFor(() => expect(Element.prototype.scrollIntoView).toHaveBeenCalled());
    expect(vi.mocked(Element.prototype.scrollIntoView).mock.contexts[0]).toBe(events);
  });

  it('events say how far away they are; the next one stands out', async () => {
    mockApi({
      'GET /events/upcoming': () => ({
        generatedAt: '2026-10-30T09:00:00Z',
        items: [
          event(1, '2026-11-12T00:00:00Z'),
          event(2, '2026-11-19T00:00:00Z'),
          event(3, '2027-01-21T00:00:00Z'),
        ],
      }),
    });
    renderApp('/');
    const section = await board('Coming up');
    const rows = await within(section).findAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('In 13 days');
    expect(rows[0]?.querySelector('.h2-status-pill--next')).not.toBeNull();
    expect(rows[1]).toHaveTextContent('In 3 weeks');
    expect(rows[2]).toHaveTextContent('In 3 months');
    expect(rows[2]?.querySelector('.h2-status-pill--later')).not.toBeNull();
    expect(rows[0]).toHaveTextContent('Conference');
    expect(rows[0]).toHaveTextContent('Zurich');
  });

  it('/?q=… still shows results and names /search as the canonical page', async () => {
    mockApi();
    renderApp('/?q=mcp');
    // The row's title and the preview's title: both link to the resource page.
    expect(await screen.findAllByRole('link', { name: 'Acme Translator' })).toHaveLength(2);
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
    mockApi({ 'GET /recent': () => makeRecent({ items: news(6) }) });
    const { container } = renderApp('/');
    await screen.findByRole('link', { name: 'News item 0' });
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });
});
