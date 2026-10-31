import { screen, within } from '@testing-library/react';
import { makeCard } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import type { ResultCard } from '@/api/types';

const day = (value: string) => ({ value, precision: 'DAY' as const, status: 'KNOWN' as const, sourceId: 'ai-deadlines', alternatives: [] });

let nextId = 0;

function event(title: string, start: string, end: string | null, location: string | null): ResultCard {
  const base = makeCard();
  nextId += 1;
  return makeCard({
    id: `99999999-9999-5999-8999-${String(nextId).padStart(12, '0')}`, title, type: 'EVENT', location,
    timeline: { ...base.timeline, eventStart: day(start), eventEnd: end ? day(end) : null },
    sources: [{ sourceId: 'ai-deadlines', sourceName: 'AI conference dates (huggingface/ai-deadlines)', url: 'https://neurips.cc', mode: 'INDEXED', retrievedAt: '2026-10-27T09:00:00Z' }],
  });
}

describe('home page Events board', () => {
  it('lists upcoming events with their stated days, place and source', async () => {
    mockApi({ 'GET /events/upcoming': () => ({ generatedAt: '2026-10-27T09:00:00Z', items: [
      event('NeurIPS 2026', '2026-12-06T00:00:00Z', '2026-12-12T00:00:00Z', 'Sydney, Australia'),
      event('One-day Summit', '2027-01-15T00:00:00Z', null, null),
      event('Far Future Conf', '2027-03-01T00:00:00Z', null, null),
    ] }) });
    renderApp('/');
    const section = (await screen.findByRole('heading', { name: 'Events' })).closest('section') as HTMLElement;
    const rows = await within(await within(section).findByRole('list', { name: 'Next events' })).findAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('NeurIPS 2026');
    expect(rows[0]).toHaveTextContent('Sydney, Australia');
    expect(rows[0]).toHaveTextContent('Dec 6');
    expect(rows[0]).toHaveTextContent('In 6 weeks');
    expect(rows[1]).toHaveTextContent('Jan 15');
    expect(rows[1]).toHaveTextContent('In 3 months');
    expect(within(rows[0] as HTMLElement).getByRole('link', { name: 'NeurIPS 2026' })).toHaveAttribute('href', expect.stringContaining('/resource/'));

    // More than three months after generatedAt (27 Oct 2026): not listed.
    expect(within(section).queryByText('Far Future Conf')).not.toBeInTheDocument();
  });

  it('shows the kind and format the source states, and nothing it does not', async () => {
    mockApi({ 'GET /events/upcoming': () => ({ generatedAt: '2026-10-27T09:00:00Z', items: [
      { ...event('Agents Hackathon', '2026-11-14T00:00:00Z', null, null), eventKind: 'HACKATHON', eventFormat: 'ONLINE' },
      { ...event('Zurich AI Meetup', '2026-11-20T00:00:00Z', null, 'Zurich, Switzerland'), eventKind: 'MEETUP', eventFormat: null },
      event('Unlabelled Day', '2026-11-25T00:00:00Z', null, null),
    ] }) });
    renderApp('/');
    const section = (await screen.findByRole('heading', { name: 'Events' })).closest('section') as HTMLElement;
    const rows = await within(await within(section).findByRole('list', { name: 'Next events' })).findAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('Hackathon · Online');
    expect(rows[1]).toHaveTextContent('Meetup');
    expect(rows[1]).not.toHaveTextContent('Meetup ·');
    expect(rows[2]).not.toHaveTextContent(/Hackathon|Meetup|Conference|Talk|Workshop/);
  });

  it('does not read the rows aloud on load: only loading, error and empty are announced (ADR 0040, 2)', async () => {
    mockApi({ 'GET /events/upcoming': () => ({ generatedAt: '2026-10-27T09:00:00Z', items: [
      event('NeurIPS 2026', '2026-12-06T00:00:00Z', null, 'Sydney, Australia'),
    ] }) });
    renderApp('/');
    const section = (await screen.findByRole('heading', { name: 'Events' })).closest('section') as HTMLElement;
    const list = await within(section).findByRole('list', { name: 'Next events' });
    expect(list.closest('[aria-live]')).toBeNull();
    expect(section.querySelector('[aria-live]')).not.toBeNull();
  });

  it('says so when no event has stated dates', async () => {
    mockApi();
    renderApp('/');
    // The empty board is a designed state: a heading, with no next step (the box above it is one).
    expect(await screen.findByRole('heading', { level: 3, name: 'No events with stated dates in the next three months.' })).toBeInTheDocument();
  });
});
