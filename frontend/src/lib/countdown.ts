/** How far away a day is, in words a board shows: Today, Tomorrow, In N days (< 14), In N weeks (< 45 days), else In N months. */
export type Countdown =
  | { key: 'today' }
  | { key: 'tomorrow' }
  | { key: 'days' | 'weeks' | 'months'; count: number };

const DAY_MS = 86_400_000;

/** Whole calendar days from [now] to [day], both read as UTC days. */
export function daysUntil(day: string, now: number): number {
  const start = Date.parse(day);
  const today = Math.floor(now / DAY_MS) * DAY_MS;
  return Math.round((Math.floor(start / DAY_MS) * DAY_MS - today) / DAY_MS);
}

export function countdown(days: number): Countdown {
  if (days <= 0) return { key: 'today' };
  if (days === 1) return { key: 'tomorrow' };
  if (days < 14) return { key: 'days', count: days };
  if (days < 45) return { key: 'weeks', count: Math.round(days / 7) };
  return { key: 'months', count: Math.max(1, Math.round(days / 30)) };
}
