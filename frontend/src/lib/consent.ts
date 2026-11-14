export const CONSENT_KEY = 'h2.consent';
const VERSION = 1;

export interface Consent {
  analytics: boolean;
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
  } catch {}
  return consent;
}
