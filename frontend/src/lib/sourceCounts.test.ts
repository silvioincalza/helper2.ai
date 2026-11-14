import { SOURCES } from '@/test/fixtures';
import { listTotal, newThisWeek } from './sourceCounts';

describe('the API fields the design reads (ADR 0040, decision 16)', () => {
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
});
