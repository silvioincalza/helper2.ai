import type { GuideResponse } from '@/api/types';
import { guidePicks, picksById } from './selection';

const rows = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];

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
