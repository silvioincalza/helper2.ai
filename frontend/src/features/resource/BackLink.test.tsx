import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BOOKMARK_KEY } from '@/lib/bookmarks';
import { makeCard, makeDetail, makeRecent } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp } from '@/test/render';

const ID = '99999999-9999-5999-8999-999999999999';
const day = (value: string) => ({
  value,
  precision: 'DAY' as const,
  status: 'KNOWN' as const,
  sourceId: 'ai-deadlines',
  alternatives: [],
});
const base = makeCard();
const STORY = makeCard({
  id: ID,
  title: 'Lead story',
  type: 'NEWS',
  timeline: { ...base.timeline, published: day('2026-11-06T00:00:00Z') },
});
const EVENT = makeCard({
  id: ID,
  title: 'NeurIPS 2026',
  type: 'EVENT',
  eventKind: 'CONFERENCE',
  eventFormat: 'IN_PERSON',
  location: null,
  timeline: { ...base.timeline, eventStart: day('2026-12-06'), eventEnd: null },
});

describe('Feature: the back link of a resource page names the list it was opened from', () => {
  afterEach(() => window.localStorage.clear());

  it.each([
    {
      list: 'News',
      entry: '/news?site=github.com',
      card: STORY,
      api: { 'GET /recent': () => makeRecent({ items: [STORY], total: 1 }) },
      label: 'Back to news',
    },
    {
      list: 'Events',
      entry: '/events',
      card: EVENT,
      api: {
        'GET /events/upcoming': () => ({ generatedAt: '2026-11-07T09:00:00Z', items: [EVENT] }),
      },
      label: 'Back to events',
    },
    {
      list: 'the Home lists',
      entry: '/',
      card: STORY,
      api: { 'GET /recent': () => makeRecent({ items: [STORY], total: 1 }) },
      label: 'Back',
    },
    {
      list: 'Saved',
      entry: '/saved',
      card: STORY,
      api: {},
      label: 'Back to saved',
    },
  ])(
    'Scenario: opened from $list, the link says "← $label" and goes back in history to the same list',
    async ({ entry, card, api, label }) => {
      window.localStorage.setItem(
        BOOKMARK_KEY,
        JSON.stringify([
          {
            resourceId: card.id,
            title: card.title,
            url: card.url,
            type: card.type,
            savedAt: '2026-10-20T10:00:00Z',
          },
        ]),
      );
      mockApi({ ...api, [`GET /resources/${ID}`]: () => makeDetail({ card }) });
      const { router } = renderApp(entry);
      const row = (await screen.findAllByRole('link', { name: card.title }))[0]!;
      await userEvent.click(row);
      await screen.findByRole('heading', { level: 1, name: card.title });

      const back = screen.getByRole('link', { name: label });
      expect(back).toHaveTextContent(`← ${label}`);
      expect(back).toHaveAttribute('href', entry);
      await userEvent.click(back);
      const { pathname, search } = router.state.location;
      expect(`${pathname}${search}`).toBe(entry);
      expect(router.state.historyAction).toBe('POP');
    },
  );
});
