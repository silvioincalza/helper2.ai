import type { GuideResponse } from '@/api/types';
import { guidePicks, picksById, picksFirst, selectedResult } from './selection';

const rows = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];

describe('selectedResult', () => {
  it('selects the result whose id is in the URL', () => {
    expect(selectedResult(rows, 'b')).toEqual({ id: 'b' });
  });
  it('selects the first result when there is no id', () => {
    expect(selectedResult(rows, null)).toEqual({ id: 'a' });
  });
  it('selects the first result when the id is not in the results', () => {
    expect(selectedResult(rows, 'gone')).toEqual({ id: 'a' });
  });
  it("selects the preferred result (the guide's pick #1) when the URL names none", () => {
    expect(selectedResult(rows, null, 'c')).toEqual({ id: 'c' });
  });
  it('an id in the URL wins over the preferred result', () => {
    expect(selectedResult(rows, 'b', 'c')).toEqual({ id: 'b' });
  });
  it('a preferred result that is not loaded selects the first', () => {
    expect(selectedResult(rows, null, 'gone')).toEqual({ id: 'a' });
  });
  it('selects nothing in an empty list', () => {
    expect(selectedResult([], 'a')).toBeUndefined();
  });
});

const rec = (resourceId: string, aiReasoned = true) => ({
  resourceId,
  title: resourceId,
  url: `https://x.example/${resourceId}`,
  type: 'MCP_SERVER' as const,
  reason: `why ${resourceId}`,
  quote: `quote ${resourceId}`,
  sourceIds: [],
  aiReasoned,
  reasonCheck: 'NOT_CHECKED' as const,
});

describe('guidePicks', () => {
  it('ranks the reasoned picks from 1 in the guide order', () => {
    const answer = {
      recommendations: [rec('c'), rec('x', false), rec('a')],
    } as unknown as GuideResponse;
    expect(guidePicks(answer).map((p) => [p.rank, p.resourceId])).toEqual([
      [1, 'c'],
      [2, 'a'],
    ]);
  });
  it('has no picks without an answer', () => {
    expect(guidePicks(null)).toEqual([]);
  });
  it("finds a row's pick by id", () => {
    const answer = { recommendations: [rec('c'), rec('a')] } as unknown as GuideResponse;
    const byId = picksById(guidePicks(answer));
    expect(rows.map((r) => byId.get(r.id)?.rank ?? null)).toEqual([2, null, 1]);
  });
  it('trims the reason, so a blank one reads as no reason', () => {
    const answer = {
      recommendations: [{ ...rec('a'), reason: '  ', reasonCheck: 'REMOVED' }],
    } as unknown as GuideResponse;
    expect(guidePicks(answer)[0]).toMatchObject({ reason: '', reasonCheck: 'REMOVED', checks: [] });
  });
});

describe('picksFirst', () => {
  const answer = { recommendations: [rec('c'), rec('gone'), rec('a')] } as unknown as GuideResponse;
  it('puts the loaded picks first in the guide order, then the rest in search order, each once', () => {
    const { picked, rest } = picksFirst(rows, guidePicks(answer));
    expect(picked.map((r) => r.id)).toEqual(['c', 'a']);
    expect(rest.map((r) => r.id)).toEqual(['b']);
  });
  it('keeps the search order without picks', () => {
    expect(picksFirst(rows, [])).toEqual({ picked: [], rest: rows });
  });
});
