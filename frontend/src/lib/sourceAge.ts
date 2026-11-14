import type { SourceInfo } from '@/api/types';

const receivedAt = new WeakMap<SourceInfo, number>();

export function stampReceived(list: SourceInfo[]): SourceInfo[] {
  const now = performance.now();
  for (const s of list) receivedAt.set(s, now);
  return list;
}

export function checkedSecondsAgo(s: SourceInfo): number | null {
  if (s.lastCheckedSecondsAgo == null) return null;
  const at = receivedAt.get(s);
  const since = at === undefined ? 0 : Math.max(0, (performance.now() - at) / 1000);
  return s.lastCheckedSecondsAgo + since;
}
