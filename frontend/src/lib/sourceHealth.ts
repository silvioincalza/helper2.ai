import type { SourceInfo } from '@/api/types';

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

export function responseTimeShown(s: SourceInfo): boolean {
  return s.responseTimeMs != null || (s.enabled && s.mode === 'LIVE');
}

type Pause = { kind: 'paused'; since: string | null } | { kind: 'trying' };

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

export function creditsOf(s: SourceInfo): Credits | null {
  if (!s.metered || !s.enabled) return null;
  const c = s.webCredits;
  if (!c) return { kind: 'unavailable' };
  return c.dailyCap == null
    ? { kind: 'uncapped', used: c.usedToday }
    : { kind: 'capped', used: c.usedToday, cap: c.dailyCap };
}
