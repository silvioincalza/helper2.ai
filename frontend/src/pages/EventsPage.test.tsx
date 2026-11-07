import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ResultCard } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { makeCard } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import { byMonth } from './EventsPage';
import type { EventRow } from '@/components/home/homeFeeds';

const day = (value: string) => ({
  value,
  precision: 'DAY' as const,
  status: 'KNOWN' as const,
  sourceId: 'ai-deadlines',
  alternatives: [],
});

let n = 0;
const id = () => `77777777-7777-5777-8777-${String(++n).padStart(12, '0')}`;

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

describe('byMonth', () => {
  it('groups events by the month they start in, keeping the order', () => {
    const rows = ['2026-11-07', '2026-11-20', '2026-12-01'].map((start) => ({ start }) as EventRow);
    expect(byMonth(rows).map((g) => [g.month, g.rows.length])).toEqual([
      ['2026-11', 2],
      ['2026-12', 1],
    ]);
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
    const { container } = renderApp('/events');
    expect(await screen.findByRole('heading', { level: 1, name: 'Events' })).toBeInTheDocument();
    expect(
      await screen.findByRole('heading', { level: 2, name: 'November 2026' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'December 2026' })).toBeInTheDocument();
    expect(m.callsTo('GET', '/events/upcoming')[0]?.search).toContain('days=366');
    await user.click(
      within(screen.getByRole('group', { name: 'Kind of event' })).getByRole('button', {
        name: 'Hackathon',
      }),
    );
    expect(screen.getByRole('link', { name: 'Agent Rush' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'CIKM 2026' })).toBeNull();
    expect(await axeViolations(container)).toEqual([]);
  });
});
