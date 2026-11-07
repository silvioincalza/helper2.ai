import type { SourceInfo } from '@/api/types';

/**
 * When each source of GET /sources came from the server, on this page's own clock (performance.now): the radar
 * reuses a cached answer for minutes, and a Home tab stays open, so "checked 2 minutes ago" must keep counting.
 * The server's lastCheckedSecondsAgo stays the base: this browser's wall clock may be wrong.
 */
const receivedAt = new WeakMap<SourceInfo, number>();

/** Marks every source of [list] as received now; returns the list. */
export function stampReceived(list: SourceInfo[]): SourceInfo[] {
  const now = performance.now();
  for (const s of list) receivedAt.set(s, now);
  return list;
}

/** Seconds since [s] was last checked, by the server's clock, plus the time since this page received it. */
export function checkedSecondsAgo(s: SourceInfo): number | null {
  if (s.lastCheckedSecondsAgo == null) return null;
  const at = receivedAt.get(s);
  const since = at === undefined ? 0 : Math.max(0, (performance.now() - at) / 1000);
  return s.lastCheckedSecondsAgo + since;
}
