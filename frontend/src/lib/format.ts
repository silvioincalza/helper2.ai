import type { DateFact } from '@/api/types';

export function formatDate(
  iso: string,
  lang: string,
  precision: DateFact['precision'] = 'DAY',
): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  if (precision === 'INSTANT') {
    return (
      new Intl.DateTimeFormat(lang, {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone: 'UTC',
      }).format(d) + ' UTC'
    );
  }
  return new Intl.DateTimeFormat(lang, { dateStyle: 'medium', timeZone: 'UTC' }).format(d);
}

/**
 * Day and month, read as a UTC calendar day: "26 Oct". With [now] (the server's clock), a date in another year also
 * shows its year: "12 Jan 2027". Empty for a date it cannot read.
 */
export function shortDay(iso: string, lang: string, now?: number): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const otherYear =
    now !== undefined &&
    !Number.isNaN(now) &&
    new Date(now).getUTCFullYear() !== d.getUTCFullYear();
  return new Intl.DateTimeFormat(lang, {
    day: 'numeric',
    month: 'short',
    ...(otherYear ? { year: 'numeric' as const } : {}),
    timeZone: 'UTC',
  }).format(d);
}

/** The host of [url] without "www.", for "Open on …" and the row's source line; the url itself when it does not parse. */
export function displayHost(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

/** Only http(s) URLs may be rendered as links. */
export function safeHref(url: string): string | undefined {
  try {
    const u = new URL(url);
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.toString() : undefined;
  } catch {
    return undefined;
  }
}
