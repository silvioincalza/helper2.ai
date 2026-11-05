import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ResultCard } from '@/api/types';
import { makeCard, makeRecent } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { mockViewport } from '@/test/viewport';

const day = (value: string, sourceId = 'github') => ({
  value,
  precision: 'DAY' as const,
  status: 'KNOWN' as const,
  sourceId,
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

let nextId = 0;
function event(
  title: string,
  start: string,
  end: string | null = null,
  over: Partial<ResultCard> = {},
): ResultCard {
  const base = makeCard();
  nextId += 1;
  return makeCard({
    id: `99999999-9999-5999-8999-${String(nextId).padStart(12, '0')}`,
    title,
    type: 'EVENT',
    location: null,
    timeline: {
      ...base.timeline,
      eventStart: day(start, 'ai-deadlines'),
      eventEnd: end ? day(end, 'ai-deadlines') : null,
    },
    ...over,
  });
}

const upcoming = (items: ResultCard[], generatedAt = '2026-11-05T09:00:00Z') => ({
  'GET /events/upcoming': () => ({ generatedAt, items }),
});

const table = async (name: string) => {
  const section = (await screen.findByRole('heading', { name, level: 2 })).closest(
    'section',
  ) as HTMLElement;
  return { section, table: await within(section).findByRole('table', { name }) };
};
const bodyRows = (t: HTMLElement) => within(t).getAllByRole('row').slice(1);

describe('New this week and Coming up on a wide screen: two tables like ls -l', () => {
  it('a table has column headers: date, type, title, source', async () => {
    mockApi({ 'GET /recent': () => makeRecent({ items: news(2) }) });
    renderApp('/');
    const { table: newsTable } = await table('New this week');
    expect(
      within(newsTable)
        .getAllByRole('columnheader')
        .map((h) => h.textContent),
    ).toEqual(['Date', 'Type', 'Title', 'Source']);
    await within(newsTable).findByRole('link', { name: 'News item 0' });
    const [first] = bodyRows(newsTable);
    expect(first).toHaveTextContent('Oct 26');
    expect(first).toHaveTextContent('MCP server');
    expect(first).toHaveTextContent('github.com');
    expect(within(first!).getByRole('link', { name: 'News item 0' })).toHaveAttribute(
      'href',
      expect.stringContaining('/resource/'),
    );
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
  });

  it('shows six rows and opens the rest in place', async () => {
    mockApi({ 'GET /recent': () => makeRecent({ items: news(8), total: 8 }) });
    renderApp('/');
    const { section, table: t } = await table('New this week');
    await within(t).findByRole('link', { name: 'News item 0' });
    expect(bodyRows(t)).toHaveLength(6);
    const more = within(section).getByRole('button', { name: 'show all 8' });
    expect(more).toHaveTextContent('$ helper2 news --all · show all 8');
    expect(more).toHaveAttribute('aria-expanded', 'false');
    expect(more).toHaveAttribute('aria-controls', t.id);
    await userEvent.click(more);
    expect(bodyRows(t)).toHaveLength(8);
    expect(within(section).getByRole('button', { name: 'show fewer' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  it('takes the total from the API', async () => {
    mockApi({ 'GET /recent': () => makeRecent({ items: news(7), total: 38 }) });
    renderApp('/');
    const { section } = await table('New this week');
    expect(await within(section).findByRole('button', { name: 'show all 38' })).toBeInTheDocument();
    expect(within(section).getByText('38')).toBeInTheDocument();
  });

  it('a list that fits shows no button', async () => {
    mockApi({ 'GET /recent': () => makeRecent({ items: news(6) }) });
    renderApp('/');
    const { section, table: t } = await table('New this week');
    await within(t).findByRole('link', { name: 'News item 5' });
    expect(within(section).queryByRole('button', { name: /show all/ })).not.toBeInTheDocument();
  });

  it('a row with no stated date says when Helper2.ai first saw it', async () => {
    const base = makeCard();
    mockApi({
      'GET /recent': () =>
        makeRecent({
          items: [
            makeCard({
              title: 'Undated',
              timeline: {
                ...base.timeline,
                published: {
                  value: null,
                  precision: 'UNKNOWN',
                  status: 'UNKNOWN',
                  alternatives: [],
                },
                updated: { value: null, precision: 'UNKNOWN', status: 'UNKNOWN', alternatives: [] },
                discovered: day('2026-11-01T10:00:00Z', 'helper2'),
              },
            }),
          ],
        }),
    });
    renderApp('/');
    const { table: t } = await table('New this week');
    await within(t).findByRole('link', { name: 'Undated' });
    expect(bodyRows(t)[0]).toHaveTextContent('seen Nov 1');
  });

  it('Coming up: when, type, event, where; a running event is on now', async () => {
    const m = mockApi(
      upcoming([
        event('Agent Hackathon', '2026-11-03T00:00:00Z', '2026-11-08T00:00:00Z', {
          eventKind: 'HACKATHON',
          eventFormat: 'ONLINE',
        }),
        event('Zurich AI Meetup', '2026-11-20T00:00:00Z', null, {
          eventKind: 'MEETUP',
          location: 'Zurich, Switzerland',
        }),
        event('Unlabelled Day', '2026-11-25T00:00:00Z'),
      ]),
    );
    renderApp('/');
    const { section, table: t } = await table('Coming up');
    expect(
      within(t)
        .getAllByRole('columnheader')
        .map((h) => h.textContent),
    ).toEqual(['When', 'Type', 'Event', 'Where']);
    await within(t).findByRole('link', { name: 'Agent Hackathon' });
    const [running, meetup, unlabelled] = bodyRows(t);
    expect(running).toHaveTextContent('on now');
    expect(running!.querySelector('.v4-on-now')).not.toBeNull();
    expect(running).toHaveTextContent('Hackathon');
    expect(running).toHaveTextContent('Online');
    expect(meetup).toHaveTextContent('Nov 20');
    expect(meetup).toHaveTextContent('Zurich, Switzerland');
    expect(unlabelled).not.toHaveTextContent(/Hackathon|Meetup|Conference|Talk|Workshop/);
    // The next three months is the API's window, so its total counts the same events.
    expect(m.callsTo('GET', '/events/upcoming')[0]?.search).toContain('days=90');
    expect(section).toBeInTheDocument();
  });

  it('does not read the rows aloud on load: only loading, error and empty are announced', async () => {
    mockApi(upcoming([event('NeurIPS 2026', '2026-12-06T00:00:00Z')]));
    renderApp('/');
    const { section, table: t } = await table('Coming up');
    await within(t).findByRole('link', { name: 'NeurIPS 2026' });
    expect(t.closest('[aria-live]')).toBeNull();
    expect(section.querySelector('[aria-live]')).not.toBeNull();
  });

  it('says so when no event has stated dates, and offers Try again on an error', async () => {
    mockApi({
      'GET /recent': () =>
        new Response('{}', { status: 500, headers: { 'content-type': 'application/json' } }),
    });
    renderApp('/');
    expect(
      await screen.findByRole('heading', {
        level: 3,
        name: 'No events with stated dates in the next three months.',
      }),
    ).toBeInTheDocument();
    const news = (await screen.findByRole('heading', { name: 'New this week', level: 2 })).closest(
      'section',
    ) as HTMLElement;
    expect(await within(news).findByRole('alert')).toBeInTheDocument();
    expect(within(news).getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});

describe('New this week and Coming up on a phone: tabs with log rows', () => {
  beforeEach(() => mockViewport(390));

  it('is one tablist with two tabs and their counts; no table', async () => {
    mockApi({
      'GET /recent': () => makeRecent({ items: news(3) }),
      ...upcoming([event('Meetup', '2026-11-07T00:00:00Z', null, { location: 'Zurich' })]),
    });
    renderApp('/');
    const tablist = await screen.findByRole('tablist', { name: 'New this week and Coming up' });
    const [newTab, eventsTab] = within(tablist).getAllByRole('tab');
    expect(await within(tablist).findByRole('tab', { name: 'New this week 3' })).toBe(newTab);
    expect(await within(tablist).findByRole('tab', { name: 'Coming up 1' })).toBe(eventsTab);
    expect(newTab).toHaveAttribute('aria-selected', 'true');
    expect(newTab).toHaveAttribute('tabindex', '0');
    expect(eventsTab).toHaveAttribute('tabindex', '-1');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    const panel = screen.getByRole('tabpanel');
    expect(panel).toHaveAttribute('aria-labelledby', 'news');
    const rows = within(panel).getAllByRole('listitem');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent('Oct 26 [MCP server]');
    expect(rows[0]).toHaveTextContent('github.com');
  });

  it('arrow keys, Home and End move between the tabs and select them', async () => {
    mockApi(upcoming([event('Meetup', '2026-11-07T00:00:00Z', null, { location: 'Zurich' })]));
    const user = userEvent.setup();
    renderApp('/');
    const [newTab, eventsTab] = within(await screen.findByRole('tablist')).getAllByRole('tab');
    await user.click(newTab!);
    await user.keyboard('{ArrowRight}');
    expect(eventsTab).toHaveFocus();
    expect(eventsTab).toHaveAttribute('aria-selected', 'true');
    const panel = screen.getByRole('tabpanel');
    expect(panel).toHaveAttribute('aria-labelledby', 'events');
    // Log row: date and [kind], the title, then how far away and where.
    expect(await within(panel).findByRole('listitem')).toHaveTextContent('In 2 days · Zurich');
    await user.keyboard('{ArrowRight}');
    expect(newTab).toHaveFocus();
    await user.keyboard('{End}');
    expect(eventsTab).toHaveFocus();
    await user.keyboard('{Home}');
    expect(newTab).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{ArrowLeft}');
    expect(eventsTab).toHaveAttribute('aria-selected', 'true');
  });

  it('/#events opens on the Coming up tab', async () => {
    mockApi();
    renderApp('/#events');
    const tab = await screen.findByRole('tab', { name: /Coming up/ });
    expect(tab).toHaveAttribute('aria-selected', 'true');
    await settle();
  });
});
