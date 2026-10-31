import type { TFunction } from 'i18next';
import type { GuideResponse, GuideStep } from '@/api/types';

/** How the guide read the question: from the `understood` event while it works, or from the final answer. */
export interface Reading {
  intent?: GuideResponse['intent'];
  subjects?: string[] | undefined;
  window?: GuideResponse['window'];
  /** Requirements the planner wrote (AI text). Only the final answer has them. */
  constraints?: string[] | undefined;
}

export interface ReadingPart {
  key: string;
  label: string;
  /** What ✕ removes when asking again; absent when the part cannot be removed. */
  drop?: string | undefined;
  /** The planner wrote the label (ADR 0038). Intent and period labels are our own text; subjects are the user's words. */
  ai?: boolean;
}

/** The parts of a reading as chips, in a fixed order. [today] (YYYY-MM-DD) tells a coming period from a past one. */
export function readingParts(reading: Reading, today: string, t: TFunction): ReadingPart[] {
  const intent = reading.intent ?? 'SUGGEST';
  const parts: ReadingPart[] = [];
  // A period alone reads as "newest" on the server, so removing that intent would come straight back: the period's ✕ is the way out.
  const intentFromWindow = intent === 'NEWEST' && reading.window != null;
  if (intent !== 'SUGGEST') parts.push({ key: 'intent', label: t(`guide.intent.${intent}`), drop: intentFromWindow ? undefined : 'intent' });
  for (const s of reading.subjects ?? []) parts.push({ key: `s:${s}`, label: s });
  if (reading.window) {
    const next = reading.window.from >= today;
    parts.push({
      key: 'window',
      label: t(next ? 'guide.windowNext' : 'guide.window', { from: reading.window.from, to: reading.window.to }),
      drop: 'window',
    });
  }
  for (const c of reading.constraints ?? []) parts.push({ key: `c:${c}`, label: c, drop: `constraint:${c}`, ai: true });
  return parts;
}

/** What the steps so far tell while the guide works: how it read the question, and how many results it reads. */
export interface Progress {
  reading: Reading | null;
  candidates: number | undefined;
}

export function progressOf(steps: readonly GuideStep[]): Progress {
  let reading: Reading | null = null;
  let candidates: number | undefined;
  for (const s of steps) {
    if (s.kind === 'understood') reading = { intent: s.data.intent, subjects: s.data.subjects, window: s.data.window ?? null };
    else if (s.kind === 'retrieved') candidates = s.data.candidates;
  }
  return { reading, candidates };
}
