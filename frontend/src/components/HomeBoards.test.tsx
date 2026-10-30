import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { known, makeCard, makeRecent, unknownDate } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import type { ResultCard } from '@/api/types';

const day = (value: string) => ({ value, precision: 'DAY' as const, status: 'KNOWN' as const, sourceId: 'ai-deadlines', alternatives: [] });

let nextId = 0;

function event(title: string, start: string, end: string | null, location: string | null, over: Partial<ResultCard> = {}): ResultCard {
  const base = makeCard();
  nextId += 1;
  return makeCard({
    id: `99999999-9999-5999-8999-${String(nextId).padStart(12, '0')}`, title, type: 'EVENT', location,
    timeline: { ...base.timeline, eventStart: day(start), eventEnd: end ? day(end) : null },
    sources: [{ sourceId: 'ai-deadlines', sourceName: 'AI conference dates', url: 'https://neurips.cc', mode: 'INDEXED', retrievedAt: '2026-10-27T09:00:00Z' }],
    ...over,
  });
}

async function board(name: 'News' | 'Events'): Promise<HTMLElement> {
  return screen.findByRole('region', { name });
}

describe('home page: the News board', () => {
  it('lists what is new from the index in the last 7 days, with one request and no live search', async () => {
    const m = mockApi();
    renderApp('/');
    const news = await board('News');
    expect(await within(news).findByRole('link', { name: 'Fresh MCP server' })).toHaveAttribute('href', expect.stringContaining('/resource/'));
    const first = new URLSearchParams(m.callsTo('GET', '/recent')[0]?.search);
    expect(first.get('days')).toBe('7');
    // News and events come from a web-search API whose terms do not clearly allow public redisplay (docs/sources.md).
    expect(first.getAll('type')).not.toContain('NEWS');
    expect(m.callsTo('GET', '/recent')).toHaveLength(1);
    expect(m.callsTo('GET', '/search')).toHaveLength(0);
  });

  it('gives each row its stated date, type tag, source and a status pill that says which date it is', async () => {
    mockApi({ 'GET /recent': () => makeRecent({ items: [
      makeCard({ id: '33333333-3333-5333-8333-000000000001', title: 'Published model', type: 'MODEL', timeline: { published: known('2026-10-20T00:00:00Z'), updated: unknownDate, discovered: known('2026-10-21T00:00:00Z', 'helper2') } }),
      makeCard({ id: '33333333-3333-5333-8333-000000000002', title: 'Updated library', type: 'LIBRARY', timeline: { published: unknownDate, updated: known('2026-10-19T00:00:00Z'), discovered: known('2026-10-21T00:00:00Z', 'helper2') } }),
      makeCard({ id: '33333333-3333-5333-8333-000000000003', title: 'Seen paper', type: 'PAPER', timeline: { published: unknownDate, updated: unknownDate, discovered: known('2026-10-18T00:00:00Z', 'helper2') } }),
    ] }) });
    renderApp('/');
    const rows = await within(await board('News')).findAllByRole('listitem');
    expect(rows[0]).toHaveTextContent(/Oct 20, 2026.*Model.*GitHub.*Published.*Published model/);
    expect(within(rows[0] as HTMLElement).getByText('Published')).toHaveClass('h2-status-pill--ok');
    expect(rows[1]).toHaveTextContent(/Oct 19, 2026.*Library.*Updated/);
    expect(within(rows[2] as HTMLElement).getByText('First seen')).toHaveClass('h2-status-pill--warn');
  });

  it('says so when nothing has a stated date', async () => {
    mockApi({ 'GET /recent': () => makeRecent({ items: [] }) });
    renderApp('/');
    expect(await within(await board('News')).findByText(/Nothing with a stated publication date/)).toBeInTheDocument();
  });

  it('on error says so, and Try again asks again', async () => {
    let calls = 0;
    mockApi({ 'GET /recent': () => { calls += 1; return calls === 1 ? problem(500, 'Server error') : makeRecent(); } });
    renderApp('/');
    const news = await board('News');
    expect(await within(news).findByRole('alert')).toHaveTextContent('Could not load the news right now.');
    await userEvent.click(within(news).getByRole('button', { name: 'Try again' }));
    expect(await within(news).findByRole('link', { name: 'Fresh MCP server' })).toBeInTheDocument();
  });
});

describe('home page: the Events board', () => {
  it('lists the next three months with stated days, place, and when each starts', async () => {
    mockApi({ 'GET /events/upcoming': () => ({ generatedAt: '2026-10-27T09:00:00Z', items: [
      event('NeurIPS 2026', '2026-11-09T00:00:00Z', '2026-11-12T00:00:00Z', 'Sydney, Australia'),
      event('One-day Summit', '2027-01-15T00:00:00Z', null, null),
      event('Far Future Conf', '2027-03-01T00:00:00Z', null, null),
    ] }) });
    renderApp('/');
    const events = await board('Events');
    const rows = await within(events).findAllByRole('listitem');
    expect(rows[0]).toHaveTextContent(/Nov.*9.*12.*2026|9.*12 Nov 2026/);
    expect(rows[0]).toHaveTextContent('Sydney, Australia');
    expect(within(rows[0] as HTMLElement).getByText('in 13 days')).toHaveClass('h2-status-pill--next');
    expect(within(rows[0] as HTMLElement).getByRole('link', { name: 'NeurIPS 2026' })).toHaveAttribute('href', expect.stringContaining('/resource/'));
    // No place: the source names where the dates come from.
    expect(rows[1]).toHaveTextContent('AI conference dates');
    expect(within(rows[1] as HTMLElement).getByText('in 80 days')).toHaveClass('h2-status-pill--later');
    // More than three months after generatedAt (27 Oct 2026): not listed.
    expect(within(events).queryByText('Far Future Conf')).not.toBeInTheDocument();
  });

  it('an event that has started and not ended is on now', async () => {
    mockApi({ 'GET /events/upcoming': () => ({ generatedAt: '2026-10-27T09:00:00Z', items: [
      event('Week-long Sprint', '2026-10-25T00:00:00Z', '2026-10-30T00:00:00Z', null),
    ] }) });
    renderApp('/');
    const rows = await within(await board('Events')).findAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('On now');
  });

  it('shows the kind and format the source states as the tag and place, and nothing it does not', async () => {
    mockApi({ 'GET /events/upcoming': () => ({ generatedAt: '2026-10-27T09:00:00Z', items: [
      event('Agents Hackathon', '2026-11-14T00:00:00Z', null, null, { eventKind: 'HACKATHON', eventFormat: 'ONLINE' }),
      event('Zurich AI Meetup', '2026-11-20T00:00:00Z', null, 'Zurich, Switzerland', { eventKind: 'MEETUP', eventFormat: null }),
      event('Unlabelled Day', '2026-11-25T00:00:00Z', null, null),
    ] }) });
    renderApp('/');
    const rows = await within(await board('Events')).findAllByRole('listitem');
    expect(within(rows[0] as HTMLElement).getByText('Hackathon')).toHaveClass('h2-type-tag');
    expect(rows[0]).toHaveTextContent('Online');
    expect(rows[1]).toHaveTextContent('Zurich, Switzerland');
    expect(within(rows[2] as HTMLElement).getByText('Event')).toHaveClass('h2-type-tag');
  });

  it('says so when no event has stated dates', async () => {
    mockApi();
    renderApp('/');
    expect(await within(await board('Events')).findByText('No events with stated dates in the next three months.')).toBeInTheDocument();
  });
});

describe('home page: both boards', () => {
  it('while loading, show skeleton rows and are not live regions, so a page load reads no list aloud', async () => {
    mockApi({ 'GET /recent': () => new Promise(() => undefined), 'GET /events/upcoming': () => new Promise(() => undefined) });
    renderApp('/');
    await board('News');
    expect(document.querySelectorAll('[aria-busy="true"]').length).toBeGreaterThanOrEqual(2);
    expect(document.querySelectorAll('.h2-board .skeleton-row').length).toBeGreaterThanOrEqual(8);
    expect(document.querySelectorAll('.h2-board [aria-live], .h2-board [role="status"]')).toHaveLength(0);
  });

  it('pass axe with rows in both', async () => {
    mockApi({ 'GET /events/upcoming': () => ({ generatedAt: '2026-10-27T09:00:00Z', items: [event('NeurIPS 2026', '2026-12-06T00:00:00Z', null, 'Sydney')] }) });
    const { container } = renderApp('/');
    await within(await board('Events')).findByRole('link', { name: 'NeurIPS 2026' });
    await within(await board('News')).findByRole('link', { name: 'Fresh MCP server' });
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });
});
