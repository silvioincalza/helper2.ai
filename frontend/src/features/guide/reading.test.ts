import type { TFunction } from 'i18next';
import type { GuideStep } from '@/api/types';
import { progressOf, readingParts } from './reading';

const t = ((key: string) => key) as unknown as TFunction;

describe('readingParts', () => {
  it('a requirement can be removed when it fits in the URL', () => {
    const [part] = readingParts({ constraints: ['free'] }, '2026-11-02', t);
    expect(part).toMatchObject({ label: 'free', drop: 'constraint:free', ai: true });
  });

  it('a requirement too long for the URL shows no ✕: removing it would do nothing', () => {
    const long = 'works offline on a laptop with no GPU and supports more than forty languages';
    expect(`constraint:${long}`.length).toBeGreaterThan(80);
    const [part] = readingParts({ constraints: [long] }, '2026-11-02', t);
    expect(part).toMatchObject({ label: long, drop: undefined, ai: true });
  });
});

describe('progressOf', () => {
  const reasoning: GuideStep = { kind: 'reasoning', data: { step: 'choose' } };
  const writing: GuideStep = { kind: 'writing', data: { step: 'choose' } };
  const chosen: GuideStep = { kind: 'chosen', data: { proposed: 2 } };
  const retrieved: GuideStep = { kind: 'retrieved', data: { candidates: 24, sources: [] } };

  it('says what the model call running now does', () => {
    expect(progressOf([retrieved, reasoning]).activity).toEqual({
      phase: 'reasoning',
      step: 'choose',
    });
    expect(progressOf([retrieved, reasoning, writing]).activity).toEqual({
      phase: 'writing',
      step: 'choose',
    });
  });

  it('says nothing about a model call once its step is done', () => {
    expect(progressOf([retrieved, reasoning, writing, chosen]).activity).toBeUndefined();
  });

  it('keeps the count of candidates while a model call runs', () => {
    expect(progressOf([retrieved, reasoning]).candidates).toBe(24);
  });

  it('has no activity when the guide does not stream its model calls', () => {
    expect(progressOf([retrieved, chosen]).activity).toBeUndefined();
  });
});
