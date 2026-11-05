import type { SourceInfo } from '@/api/types';
import { SOURCES } from '@/test/fixtures';
import { placeDots, toneOf } from './HomeRadar';

const NOW = Date.parse('2026-11-05T09:00:00Z');
const live = (over: Partial<SourceInfo> = {}): SourceInfo => ({
  ...SOURCES[0]!,
  mode: 'LIVE',
  lastOutcome: 'SUCCEEDED',
  responseTimeMs: 400,
  ...over,
});
const place = (sources: SourceInfo[]) => placeDots(sources, NOW, () => '');
const distance = (d: { x: number; y: number }) => Math.hypot(d.x - 640, d.y - 200);

describe('the home radar (ADR 0042): one dot per source', () => {
  it('colour = last check', () => {
    expect(toneOf(live())).toBe('live');
    expect(toneOf(live({ mode: 'INDEXED' }))).toBe('index');
    expect(toneOf(live({ lastOutcome: 'TIMED_OUT' }))).toBe('warn');
    expect(toneOf(live({ lastOutcome: 'RATE_LIMITED' }))).toBe('warn');
    expect(toneOf(live({ lastOutcome: 'FAILED' }))).toBe('failed');
    expect(toneOf(live({ mode: 'CURATED', lastOutcome: null }))).toBe('idle');
  });

  it('ring = mode, distance = speed: live sources inside, a faster answer closer', () => {
    const [fast, , slow, indexed] = place([
      live({ id: 'a', responseTimeMs: 100 }),
      live({ id: 'b' }),
      live({ id: 'c', responseTimeMs: 1900 }),
      live({ id: 'd', mode: 'INDEXED', lastCheckedAt: '2026-11-05T08:00:00Z' }),
    ]);
    expect(distance(fast!)).toBeLessThan(distance(slow!));
    expect(distance(slow!)).toBeLessThan(distance(indexed!));
  });

  it('size = new this week when GET /sources says it, else one fixed size', () => {
    const [plain, sized] = place([
      live({ id: 'a' }),
      { ...live({ id: 'b' }), newThisWeek: 25 } as SourceInfo,
    ]);
    expect(plain!.r).toBe(6);
    expect(sized!.r).toBeGreaterThan(plain!.r);
  });

  it('sources alternate left and right in fixed slots', () => {
    const dots = place([live({ id: 'a' }), live({ id: 'b' }), live({ id: 'c' })]);
    expect(dots.map((d) => d.left)).toEqual([true, false, true]);
    expect(dots[0]!.x).toBeLessThan(640);
    expect(dots[1]!.x).toBeGreaterThan(640);
  });
});
