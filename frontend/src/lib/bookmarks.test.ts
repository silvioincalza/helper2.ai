import { BOOKMARK_KEY, readLocalBookmarks, writeLocalBookmarks } from './bookmarks';

const bm = { resourceId: '22222222-2222-5222-8222-222222222222', title: 'Local one', url: 'https://l.example', type: 'MODEL' as const, savedAt: '2026-10-01T00:00:00.000Z' };

describe('bookmarks in browser storage', () => {
  it('writes the list under its key and reads it back', () => {
    expect(writeLocalBookmarks([bm])).toBe(true);
    expect(readLocalBookmarks()).toEqual([bm]);
    expect(JSON.parse(window.localStorage.getItem(BOOKMARK_KEY) ?? '[]')).toHaveLength(1);
  });

  it('reads corrupt data as an empty list and skips entries that are not bookmarks', () => {
    window.localStorage.setItem(BOOKMARK_KEY, '{not json');
    expect(readLocalBookmarks()).toEqual([]);
    window.localStorage.setItem(BOOKMARK_KEY, JSON.stringify([{ nope: 1 }, bm]));
    expect(readLocalBookmarks()).toEqual([bm]);
  });

  it('reads nothing and reports a failed write when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    expect(readLocalBookmarks()).toEqual([]);
    expect(writeLocalBookmarks([bm])).toBe(false);
  });
});
