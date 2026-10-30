import type { Bookmark } from '@/api/types';

export const BOOKMARK_KEY = 'h2.bookmarks.v1';

/** A bookmark kept in the browser has the same shape as one kept on the server. */
export type LocalBookmark = Bookmark;

export function readLocalBookmarks(): LocalBookmark[] {
  try {
    const raw = window.localStorage.getItem(BOOKMARK_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (b): b is LocalBookmark =>
        typeof b === 'object' && b !== null && typeof (b as LocalBookmark).resourceId === 'string' &&
        typeof (b as LocalBookmark).title === 'string' && typeof (b as LocalBookmark).url === 'string',
    );
  } catch {
    return [];
  }
}

/** Returns false when storage is unavailable (private mode, quota, disabled). */
export function writeLocalBookmarks(list: LocalBookmark[]): boolean {
  try {
    window.localStorage.setItem(BOOKMARK_KEY, JSON.stringify(list));
    return true;
  } catch {
    return false;
  }
}
