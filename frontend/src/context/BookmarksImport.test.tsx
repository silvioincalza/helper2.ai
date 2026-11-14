import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Bookmark, BookmarkRequest } from '@/api/types';
import { MAX_IMPORT, readLocalBookmarks, writeLocalBookmarks } from '@/lib/bookmarks';
import { USER } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp } from '@/test/render';

const SAVED_IN_BROWSER = MAX_IMPORT * 2 + 5;

function savedInBrowser(count: number): Bookmark[] {
  return Array.from({ length: count }, (_, i) => ({
    resourceId: `22222222-2222-5222-8222-${String(i).padStart(12, '0')}`,
    title: `Local ${i}`,
    url: `https://l.example/${i}`,
    type: 'MODEL' as const,
    savedAt: '2026-10-01T00:00:00.000Z',
  }));
}

function account() {
  const stored: Bookmark[] = [];
  return {
    stored,
    merge: (body: unknown) => {
      stored.push(
        ...(body as BookmarkRequest[]).map((b) => ({ ...b, savedAt: '2026-10-22T00:00:00Z' })),
      );
      return stored;
    },
  };
}

describe('Feature: the resources saved in the browser move to the account after sign-in', () => {
  it('Scenario: more than one request holds are sent in requests the API accepts', async () => {
    writeLocalBookmarks(savedInBrowser(SAVED_IN_BROWSER));
    const { stored, merge } = account();
    const m = mockApi({
      'GET /me': () => USER,
      'POST /me/bookmarks': ({ body }) => merge(body),
    });
    renderApp('/saved');

    await waitFor(() => expect(m.callsTo('POST', '/me/bookmarks')).toHaveLength(3));
    const sizes = m.callsTo('POST', '/me/bookmarks').map((c) => (c.body as unknown[]).length);
    expect(sizes).toEqual([MAX_IMPORT, MAX_IMPORT, 5]);
    expect(stored).toHaveLength(SAVED_IN_BROWSER);
    await waitFor(() => expect(readLocalBookmarks()).toEqual([]));
    expect(await screen.findByRole('link', { name: 'Local 404' })).toBeInTheDocument();
  });

  it('Scenario: a request that fails keeps what is not in the account yet, and Try again sends only that', async () => {
    writeLocalBookmarks(savedInBrowser(SAVED_IN_BROWSER));
    const { stored, merge } = account();
    let fail = true;
    const m = mockApi({
      'GET /me': () => USER,
      'POST /me/bookmarks': ({ body }) =>
        fail && stored.length === MAX_IMPORT ? problem(500, 'Boom') : merge(body),
    });
    renderApp('/saved');

    expect(await screen.findByText('Could not sync with your account.')).toBeInTheDocument();
    expect(readLocalBookmarks()).toHaveLength(SAVED_IN_BROWSER - MAX_IMPORT);
    expect(readLocalBookmarks()[0]?.title).toBe(`Local ${MAX_IMPORT}`);

    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(readLocalBookmarks()).toEqual([]));
    expect(stored).toHaveLength(SAVED_IN_BROWSER);
    const sizes = m.callsTo('POST', '/me/bookmarks').map((c) => (c.body as unknown[]).length);
    expect(sizes).toEqual([MAX_IMPORT, MAX_IMPORT, MAX_IMPORT, 5]);
  });
});
