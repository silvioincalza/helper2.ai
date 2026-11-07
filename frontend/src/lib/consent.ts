/**
 * The visitor's answer to the analytics question (ADR 0030), kept in this browser only. Nothing is asked again
 * until the purposes change (a new VERSION). Storage that throws (private mode, blocked) reads as "not answered".
 */
export const CONSENT_KEY = 'h2.consent';
const VERSION = 1;

export interface Consent {
  analytics: boolean;
  /** ISO date of the answer, so the visitor can see when they chose. */
  at: string;
}

export function readConsent(): Consent | undefined {
  try {
    const raw = window.localStorage.getItem(CONSENT_KEY);
    if (!raw) return undefined;
    const parsed = JSON.parse(raw) as { v?: unknown; analytics?: unknown; at?: unknown };
    if (
      parsed.v !== VERSION ||
      typeof parsed.analytics !== 'boolean' ||
      typeof parsed.at !== 'string'
    ) {
      return undefined;
    }
    return { analytics: parsed.analytics, at: parsed.at };
  } catch {
    return undefined;
  }
}

export function writeConsent(analytics: boolean, now: Date = new Date()): Consent {
  const consent = { analytics, at: now.toISOString() };
  try {
    window.localStorage.setItem(CONSENT_KEY, JSON.stringify({ v: VERSION, ...consent }));
  } catch {
    // Kept for this page view only; the banner asks again on the next visit.
  }
  return consent;
}
