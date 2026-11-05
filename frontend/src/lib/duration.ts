import type { TFunction } from 'i18next';

const MINUTE = 60;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
/** An average month (365.25 / 12 days), so 730 days read as 24 months and 365 days as 12 months. */
const MONTH = 30.4375 * DAY;
/** How far below a whole number of months a period may be, in months, and still read in months (about a day and a half). */
const MONTH_SLACK = 0.05;

export type DurationUnit = 'seconds' | 'minutes' | 'hours' | 'days' | 'months';

/**
 * A period in seconds as the text pages say it: months from a year up, days from two days up, then hours, minutes
 * and seconds. A period that is not a whole number of a unit falls to the next smaller unit, so the page never says
 * less than the server keeps: 36 hours stay "36 hours", not "2 days".
 */
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

/** Whether `months` average months cover the whole period and are no more than the slack above it. */
function coversInMonths(seconds: number, months: number): boolean {
  const over = months * MONTH - seconds;
  return over >= 0 && over <= MONTH_SLACK * MONTH;
}

/** A period in seconds in words: "24 hours", "7 days", "24 months". */
export function formatDuration(seconds: number, t: TFunction): string {
  const { unit, count } = durationParts(seconds);
  return t(`policy.duration.${unit}`, { count });
}
