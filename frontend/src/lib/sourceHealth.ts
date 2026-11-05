import type { SourceInfo } from '@/api/types';

/** A response time for the eye ("240 ms", "1.3 sec") and for screen readers ("240 milliseconds", "1.3 seconds"). */
export function responseTimeLabel(ms: number, lang: string): { short: string; long: string } {
  const seconds = ms >= 1000;
  const unit = seconds ? 'second' : 'millisecond';
  const value = seconds ? ms / 1000 : ms;
  const format = (unitDisplay: 'short' | 'long') =>
    new Intl.NumberFormat(lang, {
      style: 'unit',
      unit,
      unitDisplay,
      maximumFractionDigits: seconds ? 1 : 0,
    }).format(value);
  return { short: format('short'), long: format('long') };
}

/** Whether a card shows "Response time": when there is one, or on an enabled live source ("No calls yet"). */
export function responseTimeShown(s: SourceInfo): boolean {
  return s.responseTimeMs != null || (s.enabled && s.mode === 'LIVE');
}

type Pause = { kind: 'paused'; since: string | null } | { kind: 'trying' };

/** The circuit breaker in words: paused (OPEN) since when, or trying again (HALF_OPEN). Null when calls go through. */
export function pauseOf(s: SourceInfo): Pause | null {
  if (!s.enabled) return null;
  if (s.circuit === 'OPEN') return { kind: 'paused', since: s.circuitOpenedAt ?? null };
  if (s.circuit === 'HALF_OPEN') return { kind: 'trying' };
  return null;
}

type Credits =
  | { kind: 'capped'; used: number; cap: number }
  | { kind: 'uncapped'; used: number }
  | { kind: 'unavailable' };

/**
 * Today's web credits of the whole cluster, only for an enabled metered source (`metered`, ADR 0037); null for every
 * other source. On a metered source a null `webCredits` means the count cannot be read. A missing `metered` (an answer
 * from a replica not yet updated, during a rollout) counts as not metered.
 */
export function creditsOf(s: SourceInfo): Credits | null {
  if (!s.metered || !s.enabled) return null;
  const c = s.webCredits;
  if (!c) return { kind: 'unavailable' };
  return c.dailyCap == null
    ? { kind: 'uncapped', used: c.usedToday }
    : { kind: 'capped', used: c.usedToday, cap: c.dailyCap };
}

/** A UTC clock time, like the page's other times: "09:14:05 UTC", or "09:14 UTC" without seconds. */
export function clockTime(iso: string, lang: string, seconds = true): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return (
    new Intl.DateTimeFormat(lang, {
      hour: '2-digit',
      minute: '2-digit',
      ...(seconds ? { second: '2-digit' } : {}),
      hourCycle: 'h23',
      timeZone: 'UTC',
    }).format(d) + ' UTC'
  );
}
