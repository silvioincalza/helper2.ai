import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ResultCard } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { makeCard, makeRecent } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';


const day = (value: string) => ({ value, precision: 'DAY' as const, status: 'KNOWN' as const, sourceId: 'github', alternatives: [] });

function news(n: number): ResultCard[] {
  return Array.from({ length: n }, (_, i) => {
    const base = makeCard();
    return makeCard({
      id: `44444444-4444-5444-8444-${String(i).padStart(12, '0')}`, title: `News item ${i}`, type: 'MCP_SERVER',
      timeline: { ...base.timeline, published: day('2026-10-26T00:00:00Z') },
    });
  });
}

function event(i: number, start: string): ResultCard {
  const base = makeCard();
  return makeCard({
    id: `55555555-5555-5555-8555-${String(i).padStart(12, '0')}`, title: `Event ${i}`, type: 'EVENT', location: 'Zurich',
    eventKind: 'CONFERENCE', timeline: { ...base.timeline, eventStart: day(start), eventEnd: null },
  });
}

const board = async (name: string) => (await screen.findByRole('heading', { name, level: 2 })).closest('section') as HTMLElement;

describe('home: only the home page', () => {
  it('shows the hero, the box and the two boards, and no results', async () => {
    mockApi();
    renderApp('/');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Describe what you need.');
    expect(screen.getAllByRole('search')).toHaveLength(1);
    await board('News');
    await board('Events');
    expect(screen.queryByText('Acme Translator')).not.toBeInTheDocument();
    expect(screen.getByText(/No ads\./)).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toHaveTextContent('Switzerland · EU servers');
  });

  it('Enter in the box opens the results on /search', async () => {
    const m = mockApi();
    const { router, params } = renderApp('/');
    await userEvent.type(screen.getByRole('searchbox'), 'mcp github{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(params().get('q')).toBe('mcp github');
    expect(await screen.findByRole('link', { name: 'Acme Translator' })).toBeInTheDocument();
    expect(m.callsTo('GET', '/search')).toHaveLength(1);
  });

  it('typing on the home page does not move to /search before Enter', async () => {
    mockApi();
    const { router } = renderApp('/');
    await userEvent.type(screen.getByRole('searchbox'), 'rag library');
    await act(async () => { await new Promise((r) => setTimeout(r, 900)); });
    expect(router.state.location.pathname).toBe('/');
  });

  it('an example opens /search, searching and asking the guide', async () => {
    mockApi();
    const { router, params } = renderApp('/');
    await userEvent.click(screen.getByRole('button', { name: 'Postgres MCP server' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(params().get('q')).toContain('Postgres');
    expect(params().get('ask')).toContain('Postgres');
    await settle();
  });

  it('a looping board shows each row once to assistive tech and keeps the copy out of the Tab order', async () => {
    mockApi({ 'GET /recent': () => makeRecent({ items: news(6) }) });
    renderApp('/');
    const section = await board('News');
    await within(section).findByRole('link', { name: 'News item 0' });
    expect(within(section).getAllByRole('link', { name: 'News item 0' })).toHaveLength(1);
    expect(within(section).getAllByRole('listitem')).toHaveLength(6);
    const copy = section.querySelector('ul[aria-hidden="true"]');
    expect(copy).not.toBeNull();
    copy?.querySelectorAll('a').forEach((a) => expect(a).toHaveAttribute('tabindex', '-1'));
    expect(within(section).getAllByText('Published')[0]).toBeInTheDocument();
  });

  it('a tap pauses the board and a second tap plays it again', async () => {
    mockApi({ 'GET /recent': () => makeRecent({ items: news(6) }) });
    renderApp('/');
    const section = await board('News');
    await within(section).findByRole('link', { name: 'News item 0' });
    const body = section.querySelector('.h2-board__body') as HTMLElement;
    fireEvent.click(body);
    expect(within(section).getByRole('status')).toHaveTextContent('Paused · tap to play');
    expect(section).toHaveClass('is-paused');
    fireEvent.click(body);
    expect(within(section).queryByRole('status')).not.toBeInTheDocument();
    expect(section).not.toHaveClass('is-paused');
  });

  it('with reduced motion the board stands still: one list, no loop', async () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true, addEventListener: () => undefined, removeEventListener: () => undefined })));
    mockApi({ 'GET /recent': () => makeRecent({ items: news(6) }) });
    renderApp('/');
    const section = await board('News');
    await within(section).findByRole('link', { name: 'News item 0' });
    expect(section).toHaveClass('is-static');
    expect(section.querySelector('ul[aria-hidden="true"]')).toBeNull();
  });

  it('events say how far away they are; the next one stands out', async () => {
    mockApi({ 'GET /events/upcoming': () => ({ generatedAt: '2026-10-30T09:00:00Z', items: [
      event(1, '2026-11-12T00:00:00Z'), event(2, '2026-11-19T00:00:00Z'), event(3, '2027-01-21T00:00:00Z'),
    ] }) });
    renderApp('/');
    const section = await board('Events');
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
    expect(await screen.findByRole('link', { name: 'Acme Translator' })).toBeInTheDocument();
    const canonical = document.head.querySelector('link[rel="canonical"]') ?? document.querySelector('link[rel="canonical"]');
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
