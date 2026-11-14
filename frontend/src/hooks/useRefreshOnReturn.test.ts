import { isStale } from './useRefreshOnReturn';

describe('isStale', () => {
  it('an answer is stale from minAgeMs on', () => {
    expect(isStale(0, 59_999, 60_000)).toBe(false);
    expect(isStale(0, 60_000, 60_000)).toBe(true);
    expect(isStale(1_000, 120_000, 60_000)).toBe(true);
  });
});
