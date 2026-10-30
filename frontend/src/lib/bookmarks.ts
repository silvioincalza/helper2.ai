import type { Bookmark } from '@/api/types';

export const BOOKMARK_KEY = 'h2.bookmarks.v1';

export function readLocalBookmarks(): Bookmark[] {
  try {
    const raw = window.localStorage.getItem(BOOKMARK_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (b): b is Bookmark =>
        typeof b === 'object' && b !== null && typeof (b as Bookmark).resourceId === 'string' &&
        typeof (b as Bookmark).title === 'string' && typeof (b as Bookmark).url === 'string',
    );
  } catch {
    return [];
  }
}

/** Returns false when storage is unavailable (private mode, quota, disabled). */
export function writeLocalBookmarks(list: Bookmark[]): boolean {
  try {
    window.localStorage.setItem(BOOKMARK_KEY, JSON.stringify(list));
    return true;
  } catch {
    return false;
  }
}
