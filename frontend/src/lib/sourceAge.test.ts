import type { SourceInfo } from '@/api/types';
import { SOURCES } from '@/test/fixtures';
import { checkedSecondsAgo, stampReceived } from './sourceAge';

const makeSource = (over: Partial<SourceInfo>): SourceInfo => ({ ...SOURCES[0]!, ...over });

describe('how long ago a source was checked', () => {
  afterEach(() => vi.restoreAllMocks());

  it("starts from the server's age and keeps counting on this page's clock", () => {
    const clock = vi.spyOn(performance, 'now').mockReturnValue(10_000);
    const [s] = stampReceived([makeSource({ lastCheckedSecondsAgo: 120 })]);
    expect(checkedSecondsAgo(s!)).toBe(120);
    // The answer is reused from the cache 4 minutes later, or the tab stayed open.
    clock.mockReturnValue(10_000 + 4 * 60_000);
    expect(checkedSecondsAgo(s!)).toBe(360);
  });

  it('a source not stamped, or never checked, keeps what the server said', () => {
    expect(checkedSecondsAgo(makeSource({ lastCheckedSecondsAgo: 120 }))).toBe(120);
    const [never] = stampReceived([makeSource({ lastCheckedSecondsAgo: null })]);
    expect(checkedSecondsAgo(never!)).toBeNull();
  });
});
