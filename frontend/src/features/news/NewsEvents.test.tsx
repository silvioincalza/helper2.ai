import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ResultCard } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { makeCard, makeRecent } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import { topHosts } from './NewsPage';
import { byMonth } from '../events/EventsPage';
import type { EventRow, NewsRow } from '@/features/home/homeFeeds';

const day = (value: string) => ({
  value,
  precision: 'DAY' as const,
  status: 'KNOWN' as const,
  sourceId: 'ai-deadlines',
  alternatives: [],
});

let n = 0;
const id = () => `77777777-7777-5777-8777-${String(++n).padStart(12, '0')}`;

function story(title: string, host: string): ResultCard {
  const base = makeCard();
  return makeCard({
    id: id(),
    title,
    type: 'NEWS',
    sources: [{ ...base.sources[0]!, url: `https://${host}/a/${n}` }],
    timeline: { ...base.timeline, published: day('2026-11-06T00:00:00Z') },
  });
}

function event(title: string, start: string, over: Partial<ResultCard> = {}): ResultCard {
  const base = makeCard();
  return makeCard({
    id: id(),
    title,
    type: 'EVENT',
    eventKind: 'CONFERENCE',
    eventFormat: 'IN_PERSON',
    location: null,
    timeline: { ...base.timeline, eventStart: day(start), eventEnd: null },
    ...over,
  });
}

describe('topHosts and byMonth', () => {
  it('orders sites by how many stories they have, then by name', () => {
    const rows = ['b.com', 'a.com', 'b.com', 'c.com'].map((host) => ({ host }) as NewsRow);
    expect(topHosts(rows, 2)).toEqual(['b.com', 'a.com']);
  });

  it('groups events by the month they start in, keeping the order', () => {
    const rows = ['2026-11-07', '2026-11-20', '2026-12-01'].map((start) => ({ start }) as EventRow);
    expect(byMonth(rows).map((g) => [g.month, g.rows.length])).toEqual([
      ['2026-11', 2],
      ['2026-12', 1],
    ]);
  });
});

describe('News page', () => {
  it('shows the lead story, the others by site, and filters by site; only AI news is asked for', async () => {
    const m = mockApi({
      'GET /recent': () =>
        makeRecent({
          items: [
            story('Lead story', 'a.example'),
            story('Second story', 'b.example'),
            story('Third story', 'a.example'),
          ],
          total: 3,
          generatedAt: '2026-11-07T09:00:00Z',
        }),
    });
    const user = userEvent.setup();
    const { container } = renderApp('/news');
    expect(await screen.findByRole('heading', { level: 1, name: 'AI news' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Lead story' })).toBeInTheDocument();
    expect(m.callsTo('GET', '/recent')[0]?.search).toContain('type=NEWS');
    const sites = screen.getByRole('group', { name: 'Site' });
    await user.click(within(sites).getByRole('button', { name: 'b.example' }));
    expect(screen.getByRole('link', { name: 'Second story' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Lead story' })).toBeNull();
    expect(within(sites).getByRole('button', { name: 'b.example' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(container.querySelector('main [data-ai-generated]')).toBeNull();
    expect(await axeViolations(container)).toEqual([]);
  });
});

describe('News page, the front', () => {
  const week = (n: number) =>
    makeRecent({
      items: Array.from({ length: n }, (_, i) => story(`Story ${i + 1}`, 'a.example')),
      total: n,
      generatedAt: '2026-11-07T09:00:00Z',
    });

  it('fills both columns to about the same height: a lead with six under it, five beside them, the rest below', async () => {
    mockApi({ 'GET /recent': () => week(15) });
    renderApp('/news');
    await screen.findByRole('link', { name: 'Story 1' });
    expect(document.querySelectorAll('.h2-news__top .h2-story.is-lead')).toHaveLength(1);
    expect(document.querySelectorAll('.h2-news__top .h2-story.is-second')).toHaveLength(6);
    expect(document.querySelectorAll('.h2-news__latest .h2-story')).toHaveLength(5);
    expect(document.querySelectorAll('.h2-news__earlier .h2-story')).toHaveLength(3);
  });

  it('with too few stories for a Latest column, the front takes the full width', async () => {
    mockApi({ 'GET /recent': () => week(4) });
    renderApp('/news');
    await screen.findByRole('link', { name: 'Story 1' });
    expect(document.querySelector('.h2-news__latest')).toBeNull();
    expect(document.querySelector('.h2-news__front')).toHaveClass('is-alone');
  });
});

describe('News page, filtered from a link', () => {
  const recent = () =>
    makeRecent({
      items: [story('Lead story', 'a.example'), story('Second story', 'b.example')],
      total: 2,
      generatedAt: '2026-11-07T09:00:00Z',
    });

  it('keeps the site in the address, so a shared link shows the same stories', async () => {
    mockApi({ 'GET /recent': recent });
    const user = userEvent.setup();
    const { params } = renderApp('/news?site=b.example');
    expect(await screen.findByRole('link', { name: 'Second story' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Lead story' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'All sites' }));
    expect(params().get('site')).toBeNull();
    expect(screen.getByRole('link', { name: 'Lead story' })).toBeInTheDocument();
  });

  it('a site with no story this week says so, and its chip turns the filter off', async () => {
    mockApi({ 'GET /recent': recent });
    const user = userEvent.setup();
    renderApp('/news?site=gone.example');
    expect(
      await screen.findByText('No story from this site in the last week.'),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'gone.example' }));
    expect(screen.getByRole('link', { name: 'Lead story' })).toBeInTheDocument();
  });
});

describe('Events page', () => {
  it('groups the next year by month, with kind and format filters, and asks for a year', async () => {
    const m = mockApi({
      'GET /events/upcoming': () => ({
        generatedAt: '2026-11-07T09:00:00Z',
        items: [
          event('CIKM 2026', '2026-11-07'),
          event('Agent Rush', '2026-11-20', { eventKind: 'HACKATHON', eventFormat: 'ONLINE' }),
          event('NeurIPS 2026', '2026-12-06'),
        ],
      }),
    });
    const user = userEvent.setup();
    const { container, params } = renderApp('/events');
    expect(await screen.findByRole('heading', { level: 1, name: 'Events' })).toBeInTheDocument();
    expect(
      await screen.findByRole('heading', { level: 2, name: 'November 2026' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'December 2026' })).toBeInTheDocument();
    expect(m.callsTo('GET', '/events/upcoming')[0]?.search).toContain('days=366');
    expect(screen.getByText('2 events')).toBeInTheDocument();
    expect(screen.getByText('1 event')).toBeInTheDocument();
    const kinds = screen.getByRole('group', { name: 'Kind of event' });
    expect(within(kinds).getByRole('button', { name: 'All 3' })).toBeInTheDocument();
    await user.click(within(kinds).getByRole('button', { name: 'Hackathon 1' }));
    expect(params().get('kind')).toBe('HACKATHON');
    expect(screen.getByRole('link', { name: 'Agent Rush' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'CIKM 2026' })).toBeNull();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('opens with the filters of a shared link', async () => {
    mockApi({
      'GET /events/upcoming': () => ({
        generatedAt: '2026-11-07T09:00:00Z',
        items: [
          event('CIKM 2026', '2026-11-07'),
          event('Agent Rush', '2026-11-20', { eventKind: 'HACKATHON', eventFormat: 'ONLINE' }),
        ],
      }),
    });
    renderApp('/events?format=ONLINE');
    expect(await screen.findByRole('link', { name: 'Agent Rush' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'CIKM 2026' })).toBeNull();
    expect(
      within(screen.getByRole('group', { name: 'Online or in person' })).getByRole('button', {
        name: 'Online 1',
      }),
    ).toHaveAttribute('aria-pressed', 'true');
  });

  it('labels the days, kind and place an AI model read, and only those', async () => {
    mockApi({
      'GET /events/upcoming': () => ({
        generatedAt: '2026-11-07T09:00:00Z',
        items: [
          event('Zurich AI Hackathon', '2026-12-12', {
            eventKind: 'HACKATHON',
            location: 'Zurich, Switzerland',
            eventOrigin: 'AI_EXTRACTED',
          }),
          event('NeurIPS 2026', '2026-12-06', { eventOrigin: 'SOURCE_PROVIDED' }),
        ],
      }),
    });
    const { container } = renderApp('/events');
    const ai = (await screen.findByRole('link', { name: 'Zurich AI Hackathon' })).closest('li')!;
    const stated = screen.getByRole('link', { name: 'NeurIPS 2026' }).closest('li')!;
    expect(within(ai).getByText('Extracted by AI')).toBeInTheDocument();
    expect(ai.querySelector('time[data-ai-generated="true"]')).not.toBeNull();
    expect(within(ai).getByText('Hackathon').closest('[data-ai-generated]')).not.toBeNull();
    expect(
      within(ai).getByText('Zurich, Switzerland').closest('[data-ai-generated]'),
    ).not.toBeNull();
    expect(within(stated).queryByText('Extracted by AI')).toBeNull();
    expect(stated.querySelector('[data-ai-generated]')).toBeNull();
    expect(await axeViolations(container)).toEqual([]);
  });
});
