import { known, makeCard, unknownDate } from '@/test/fixtures';
import { creationDate, formatMonth, groupByCreationMonth } from './timeline';

const card = (id: string, published: ReturnType<typeof known> | typeof unknownDate) =>
  makeCard({ id, timeline: { published, updated: known('2026-10-25T00:00:00Z'), discovered: known('2026-10-26T00:00:00Z', 'helper2') } });

describe('timeline', () => {
  it('uses the creation date the source states, never the discovery (scan) date', () => {
    expect(creationDate(card('a', known('2024-02-03T00:00:00Z')))).toBe('2024-02-03T00:00:00Z');
    expect(creationDate(card('b', unknownDate))).toBeNull();
  });

  it('keeps a disputed creation date in place, as the API sorts by it', () => {
    const disputed = { value: '2025-05-01T00:00:00Z', precision: 'DAY' as const, status: 'CONFLICTING' as const, sourceId: null, alternatives: [] };
    expect(creationDate(card('c', disputed))).toBe('2025-05-01T00:00:00Z');
  });

  it('groups by UTC month in arrival order, unknown dates in their own group', () => {
    const groups = groupByCreationMonth([
      card('1', known('2026-10-17T02:40:38Z')),
      card('2', known('2026-10-01T00:00:00Z')),
      card('3', known('2026-09-30T23:30:00-02:00')), // 1 Oct in UTC
      card('4', known('2025-01-09T00:00:00Z')),
      card('5', unknownDate),
      card('6', unknownDate),
    ]);
    expect(groups.map((g) => [g.month, g.cards.map((c) => c.id)])).toEqual([
      ['2026-10', ['1', '2', '3']],
      ['2025-01', ['4']],
      [null, ['5', '6']],
    ]);
  });

  it('names the month in the reader language', () => {
    expect(formatMonth('2026-10', 'en')).toBe('October 2026');
    expect(formatMonth('2026-10', 'it')).toBe('ottobre 2026');
  });
});
