import { MAX_DROP } from '@/lib/searchState';
import type { TFunction } from 'i18next';
import type { GuideResponse, GuideStep } from '@/api/types';
import type { ModelActivity } from '@/ui/PicksBar';

interface Reading {
  intent?: GuideResponse['intent'];
  subjects?: string[] | undefined;
  window?: GuideResponse['window'];
  constraints?: string[] | undefined;
}

interface ReadingPart {
  key: string;
  label: string;
  drop?: string | undefined;
  ai?: boolean;
}

export function readingParts(reading: Reading, today: string, t: TFunction): ReadingPart[] {
  const intent = reading.intent ?? 'SUGGEST';
  const parts: ReadingPart[] = [];
  const intentFromWindow = intent === 'NEWEST' && reading.window != null;
  if (intent !== 'SUGGEST')
    parts.push({
      key: 'intent',
      label: t(`guide.intent.${intent}`),
      drop: intentFromWindow ? undefined : 'intent',
    });
  for (const s of reading.subjects ?? []) parts.push({ key: `s:${s}`, label: s });
  if (reading.window) {
    const next = reading.window.from >= today;
    parts.push({
      key: 'window',
      label: t(next ? 'guide.windowNext' : 'guide.window', {
        from: reading.window.from,
        to: reading.window.to,
      }),
      drop: 'window',
    });
  }
  for (const c of reading.constraints ?? []) {
    const drop = `constraint:${c}`;
    parts.push({
      key: `c:${c}`,
      label: c,
      drop: drop.length <= MAX_DROP ? drop : undefined,
      ai: true,
    });
  }
  return parts;
}

interface Progress {
  reading: Reading | null;
  candidates: number | undefined;
  activity: ModelActivity | undefined;
}

export function progressOf(steps: readonly GuideStep[]): Progress {
  let reading: Reading | null = null;
  let candidates: number | undefined;
  let activity: ModelActivity | undefined;
  for (const s of steps) {
    activity =
      s.kind === 'reasoning' || s.kind === 'writing'
        ? { phase: s.kind, step: s.data.step }
        : undefined;
    if (s.kind === 'understood')
      reading = { intent: s.data.intent, subjects: s.data.subjects, window: s.data.window ?? null };
    else if (s.kind === 'retrieved') candidates = s.data.candidates;
  }
  return { reading, candidates, activity };
}
