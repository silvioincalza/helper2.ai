import i18n from 'i18next';
import type { DateFact } from '@/api/types';

const FALLBACK_LANGUAGE = 'en';

function browserRegion(): string | undefined {
  try {
    return new Intl.Locale(navigator.language).region;
  } catch {
    return undefined;
  }
}

export function dateLocale(appLanguage: string = i18n.language || FALLBACK_LANGUAGE): string {
  const region = browserRegion();
  if (!region) return appLanguage;
  try {
    return new Intl.Locale(appLanguage, { region }).toString();
  } catch {
    return appLanguage;
  }
}

function instant(iso: string): Date | undefined {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? undefined : d;
}

export function formatDate(iso: string, precision: DateFact['precision'] = 'DAY'): string {
  const d = instant(iso);
  if (!d) return iso;
  if (precision === 'INSTANT')
    return new Intl.DateTimeFormat(dateLocale(), {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(d);
  return new Intl.DateTimeFormat(dateLocale(), { dateStyle: 'medium', timeZone: 'UTC' }).format(d);
}

export function localDay(iso: string): string {
  const d = instant(iso);
  return d ? new Intl.DateTimeFormat(dateLocale(), { dateStyle: 'medium' }).format(d) : iso;
}

export function longLocalDay(at: number): string {
  if (Number.isNaN(at)) return '';
  return new Intl.DateTimeFormat(dateLocale(), {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date(at));
}

export function clockTime(iso: string, seconds = true): string {
  const d = instant(iso);
  if (!d) return iso;
  return new Intl.DateTimeFormat(dateLocale(), { timeStyle: seconds ? 'medium' : 'short' }).format(
    d,
  );
}

export function shortDay(iso: string, now?: number): string {
  const d = instant(iso);
  if (!d) return '';
  const otherYear =
    now !== undefined &&
    !Number.isNaN(now) &&
    new Date(now).getUTCFullYear() !== d.getUTCFullYear();
  return new Intl.DateTimeFormat(dateLocale(), {
    day: 'numeric',
    month: 'short',
    ...(otherYear ? { year: 'numeric' as const } : {}),
    timeZone: 'UTC',
  }).format(d);
}

export function weekDay(isoDay: string): string {
  return new Intl.DateTimeFormat(dateLocale(), {
    day: 'numeric',
    weekday: 'short',
    timeZone: 'UTC',
  }).format(new Date(`${isoDay.slice(0, 10)}T00:00:00Z`));
}

export function shortMonth(iso: string): string {
  const d = instant(iso);
  if (!d) return '';
  return new Intl.DateTimeFormat(dateLocale(), {
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(d);
}

export function formatMonth(month: string): string {
  const d = instant(`${month}-01T00:00:00Z`);
  if (!d) return month;
  return new Intl.DateTimeFormat(dateLocale(), {
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(d);
}

export function relativeTime(style: 'long' | 'short' = 'long'): Intl.RelativeTimeFormat {
  return new Intl.RelativeTimeFormat(dateLocale(), { numeric: 'auto', style });
}

interface ComingTime {
  time: string;
  tomorrow: boolean;
}

export interface VisitorClock {
  now: number;
  timeZone?: string;
}

function comingTime(
  iso: string | null | undefined,
  { now, timeZone }: VisitorClock,
): ComingTime | undefined {
  if (!iso) return undefined;
  const at = instant(iso);
  if (!at || at.getTime() <= now) return undefined;
  const day = new Intl.DateTimeFormat('en-CA', { dateStyle: 'short', timeZone });
  return {
    time: new Intl.DateTimeFormat(dateLocale(), { timeStyle: 'short', timeZone }).format(at),
    tomorrow: day.format(at) !== day.format(new Date(now)),
  };
}

export function backAgain(
  keyPrefix: string,
  iso: string | null | undefined,
  clock: VisitorClock,
): { key: string; time: string } | undefined {
  const back = comingTime(iso, clock);
  if (!back) return undefined;
  return { key: `${keyPrefix}${back.tomorrow ? 'BackTomorrowAt' : 'BackAt'}`, time: back.time };
}

export function displayHost(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

export function safeHref(url: string): string | undefined {
  try {
    const u = new URL(url);
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.toString() : undefined;
  } catch {
    return undefined;
  }
}
