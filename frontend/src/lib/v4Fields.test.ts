import type { Me } from '@/api/types';
import { ANON, SOURCES } from '@/test/fixtures';
import { aiAnswers, listTotal, newThisWeek } from './v4Fields';

describe('the API fields design v4 reads (ADR 0042, decision 7)', () => {
  it('newThisWeek: the count, or null for a source without its own (default dot size)', () => {
    const source = SOURCES[0]!;
    expect(newThisWeek({ ...source, newThisWeek: 7 })).toBe(7);
    expect(newThisWeek({ ...source, newThisWeek: 0 })).toBe(0);
    expect(newThisWeek({ ...source, newThisWeek: null })).toBeNull();
    const { newThisWeek: _, ...without } = { ...source, newThisWeek: 1 };
    expect(newThisWeek(without)).toBeNull();
  });

  it('total: from the API, never fewer than the rows sent', () => {
    expect(listTotal({ total: 38 }, 6)).toBe(38);
    expect(listTotal(undefined, 0)).toBe(0);
    expect(listTotal({ total: 2 }, 6)).toBe(6);
  });

  it('limits.guideQuestions: today’s AI answers; null when the guide has no model or GET /me has not answered', () => {
    const limits = (guideQuestions: { limit: number; remaining: number } | null): Me => ({
      ...ANON,
      limits: { ...ANON.limits, guideQuestions, resetsAt: '2026-11-06T00:00:00Z' },
    });
    expect(aiAnswers(limits({ limit: 3, remaining: 2 }))).toEqual({
      remaining: 2,
      limit: 3,
      resetsAt: '2026-11-06T00:00:00Z',
    });
    expect(aiAnswers(limits(null))).toBeNull();
    expect(aiAnswers(limits({ limit: 0, remaining: 0 }))).toBeNull();
    const { limits: _, ...beforeMe } = ANON;
    expect(aiAnswers(beforeMe)).toBeNull();
  });
});
