import type { GuideResponse } from '@/api/types';
import { displayHost, guidePicks, picksById, selectedResult } from './selection';

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
  it('selects nothing in an empty list', () => {
    expect(selectedResult([], 'a')).toBeUndefined();
  });
});

const rec = (resourceId: string, aiReasoned = true) => ({
  resourceId, title: resourceId, url: `https://x.example/${resourceId}`, type: 'MCP_SERVER' as const, reason: `why ${resourceId}`,
  quote: `quote ${resourceId}`, sourceIds: [], aiReasoned, reasonCheck: 'NOT_CHECKED' as const,
});

describe('guidePicks', () => {
  it('ranks the reasoned picks from 1 in the guide order', () => {
    const answer = { recommendations: [rec('c'), rec('x', false), rec('a')] } as unknown as GuideResponse;
    expect(guidePicks(answer).map((p) => [p.rank, p.resourceId])).toEqual([[1, 'c'], [2, 'a']]);
  });
  it('has no picks without an answer', () => {
    expect(guidePicks(null)).toEqual([]);
  });
  it('never changes the order of the results: picks are looked up by id', () => {
    const answer = { recommendations: [rec('c'), rec('a')] } as unknown as GuideResponse;
    const byId = picksById(guidePicks(answer));
    expect(rows.map((r) => byId.get(r.id)?.rank ?? null)).toEqual([2, null, 1]);
    expect(rows.map((r) => r.id)).toEqual(['a', 'b', 'c']);
  });
});

describe('displayHost', () => {
  it('drops www.', () => expect(displayHost('https://www.acme.example/x')).toBe('acme.example'));
  it('keeps text that is not a URL', () => expect(displayHost('not a url')).toBe('not a url'));
});
