import type { TFunction } from 'i18next';

const MINUTE = 60;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const MONTH = 30.4375 * DAY;
const MONTH_SLACK = 0.05;

export type DurationUnit = 'seconds' | 'minutes' | 'hours' | 'days' | 'months';

export function durationParts(seconds: number): { unit: DurationUnit; count: number } {
  const months = Math.round(seconds / MONTH);
  if (seconds >= 365 * DAY && coversInMonths(seconds, months))
    return { unit: 'months', count: months };
  if (seconds >= 2 * DAY && seconds % DAY === 0) return { unit: 'days', count: seconds / DAY };
  if (seconds >= HOUR && seconds % HOUR === 0) return { unit: 'hours', count: seconds / HOUR };
  if (seconds >= MINUTE && seconds % MINUTE === 0)
    return { unit: 'minutes', count: seconds / MINUTE };
  return { unit: 'seconds', count: seconds };
}

function coversInMonths(seconds: number, months: number): boolean {
  const over = months * MONTH - seconds;
  return over >= 0 && over <= MONTH_SLACK * MONTH;
}

export function formatDuration(seconds: number, t: TFunction): string {
  const { unit, count } = durationParts(seconds);
  return t(`policy.duration.${unit}`, { count });
}
