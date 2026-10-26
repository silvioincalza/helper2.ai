import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BOOKMARK_KEY, readLocalBookmarks, writeLocalBookmarks } from '@/lib/bookmarks';
import { ANON, USER, makeSearch } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import { SAVED_ENABLED } from '@/lib/features';

const bm = { resourceId: '22222222-2222-5222-8222-222222222222', title: 'Local one', url: 'https://l.example', type: 'MODEL' as const, savedAt: '2026-10-01T00:00:00.000Z' };

// Saved is on the roadmap (lib/features.ts): these drive the hidden UI and run again when it comes back.
describe.skipIf(!SAVED_ENABLED)('bookmarks (local)', () => {
  it('reads and writes localStorage', () => {
    expect(writeLocalBookmarks([bm])).toBe(true);
    expect(readLocalBookmarks()).toEqual([bm]);
    expect(JSON.parse(window.localStorage.getItem(BOOKMARK_KEY) ?? '[]')).toHaveLength(1);
  });

  it('survives corrupt data', () => {
    window.localStorage.setItem(BOOKMARK_KEY, '{not json');
    expect(readLocalBookmarks()).toEqual([]);
    window.localStorage.setItem(BOOKMARK_KEY, JSON.stringify([{ nope: 1 }, bm]));
    expect(readLocalBookmarks()).toEqual([bm]);
  });

  it('survives localStorage throwing on read and write', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    expect(readLocalBookmarks()).toEqual([]);
    expect(writeLocalBookmarks([bm])).toBe(false);
  });

  it('saves from a result, lists on /saved and removes', async () => {
    mockApi({ 'GET /me': () => ANON });
    const { router } = renderApp('/search?q=translate');
    await userEvent.click(await screen.findByRole('button', { name: /Save Acme Translator/ }));
    expect(readLocalBookmarks()[0]).toMatchObject({ title: 'Acme Translator', type: 'PRODUCT' });
    await act(() => router.navigate('/saved'));
    await screen.findByRole('heading', { name: 'Saved resources' });
    expect(screen.getByRole('link', { name: 'Acme Translator' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Remove Acme Translator from saved/ }));
    expect(screen.getByText(/Nothing saved yet/)).toBeInTheDocument();
    expect(readLocalBookmarks()).toEqual([]);
  });

  it('keeps working in memory and warns when storage throws', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    mockApi();
    const { router } = renderApp('/search?q=translate');
    await userEvent.click(await screen.findByRole('button', { name: /Save Acme Translator/ }));
    expect(screen.getByRole('button', { name: /Save Acme Translator/ })).toHaveAttribute('aria-pressed', 'true');
    await act(() => router.navigate('/saved'));
    expect(await screen.findByRole('alert')).toHaveTextContent(/blocked storage/);
    expect(screen.getByRole('link', { name: 'Acme Translator' })).toBeInTheDocument();
  });
});

describe.skipIf(!SAVED_ENABLED)('bookmarks (signed in)', () => {
  it('merges local bookmarks into the server once, then uses the server list', async () => {
    writeLocalBookmarks([bm]);
    const server = [{ resourceId: bm.resourceId, title: 'Local one', url: bm.url, type: 'MODEL', savedAt: '2026-10-22T00:00:00Z' }, { resourceId: '33333333-3333-5333-8333-333333333333', title: 'Server only', url: 'https://s.example', type: null, savedAt: '2026-10-21T00:00:00Z' }];
    const m = mockApi({ 'GET /me': () => USER, 'POST /me/bookmarks': () => server, 'GET /search': () => makeSearch() });
    const { router } = renderApp('/saved');
    expect(await screen.findByRole('link', { name: 'Server only' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Local one' })).toBeInTheDocument();
    const posts = m.callsTo('POST', '/me/bookmarks');
    expect(posts).toHaveLength(1);
    expect(posts[0]?.body).toEqual([{ resourceId: bm.resourceId, title: 'Local one', url: bm.url, type: 'MODEL' }]);
    await waitFor(() => expect(readLocalBookmarks()).toEqual([]));
    await act(() => router.navigate('/sources'));
    await act(() => router.navigate('/saved'));
    await screen.findByRole('link', { name: 'Server only' });
    expect(m.callsTo('POST', '/me/bookmarks')).toHaveLength(1);
  });

  it('uses GET when there is nothing to merge, and DELETE on remove', async () => {
    const list = [{ resourceId: bm.resourceId, title: 'Srv', url: bm.url, type: null, savedAt: '2026-10-22T00:00:00Z' }];
    const m = mockApi({ 'GET /me': () => USER, 'GET /me/bookmarks': () => list, 'DELETE /me/bookmarks/*': () => undefined });
    renderApp('/saved');
    await userEvent.click(await screen.findByRole('button', { name: /Remove Srv from saved/ }));
    expect(m.callsTo('GET', '/me/bookmarks')).toHaveLength(1);
    await waitFor(() => expect(m.callsTo('DELETE', `/me/bookmarks/${bm.resourceId}`)).toHaveLength(1));
    expect(screen.getByText(/Nothing saved yet/)).toBeInTheDocument();
  });
});
