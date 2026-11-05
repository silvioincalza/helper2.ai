import type { SourceInfo } from '@/api/types';
import { radarSources } from '@/lib/v4Fields';
import { STAGING_SOURCES } from '@/test/fixtures';
import {
  angleOf,
  outOfBounds,
  placeDots,
  radarFrame,
  radiusOf,
  toneOf,
  touching,
  type RadarFrame,
} from './radarLayout';

const external = radarSources(STAGING_SOURCES);
/** The title block as Chromium lays it out (eyebrow, two title lines, the lede) in the hero, at 1280 and 1024 px. */
const wide = radarFrame(1280, 340, { left: 346, top: 47, right: 934, bottom: 281 });
const laptop = radarFrame(1024, 340, { left: 218, top: 47, right: 806, bottom: 281 });
const live = (over: Partial<SourceInfo> = {}): SourceInfo => ({
  ...STAGING_SOURCES[0]!,
  ...over,
});

describe('the radar (ADR 0042): which sources and how they look', () => {
  it('shows only the enabled external sources: 13 of the 16 on staging', () => {
    expect(external).toHaveLength(13);
    expect(external.map((s) => s.id)).not.toContain('helper2-index');
    expect(external.map((s) => s.id)).not.toContain('helper2-semantic');
    expect(external.map((s) => s.id)).not.toContain('curated');
    expect(radarSources([live({ enabled: false })])).toEqual([]);
  });

  it('colour = last check, every dot filled: answering, amber slow or limited, red failed', () => {
    expect(toneOf(live())).toBe('answering');
    expect(toneOf(live({ mode: 'INDEXED' }))).toBe('answering');
    expect(toneOf(live({ lastOutcome: 'FAILED' }))).toBe('failed');
    expect(toneOf(live({ lastOutcome: 'TIMED_OUT' }))).toBe('warn');
    expect(toneOf(live({ lastOutcome: 'RATE_LIMITED' }))).toBe('warn');
    expect(toneOf(live({ lastOutcome: 'SKIPPED' }))).toBe('idle');
  });

  it('size = new this week, the default size when the API sends none', () => {
    expect(radiusOf(live({ newThisWeek: null }))).toBe(6);
    expect(radiusOf(live({ newThisWeek: 25 }))).toBeGreaterThan(6);
  });
});

describe.each([
  ['1280 px', wide],
  ['1024 px', laptop],
])('placing the 13 staging sources at %s', (_, frame: RadarFrame) => {
  const dots = placeDots(external, frame);

  it('places every one', () => {
    expect(dots).toHaveLength(13);
  });

  it('no two names or dots touch', () => {
    for (const [i, a] of dots.entries())
      for (const b of dots.slice(i + 1)) expect(touching(a, b, frame.gap - 1)).toBe(false);
  });

  it('nothing under the title, nothing outside the hero (the search box is below it)', () => {
    for (const d of dots) expect(outOfBounds(d, frame)).toBe(false);
  });

  it('live sources on the inner circles, the others outside them', () => {
    const radius = (d: (typeof dots)[number]) => Math.hypot(d.x - frame.cx, d.y - frame.cy);
    const inner = dots.filter((d) => d.source.mode === 'LIVE').map(radius);
    const outer = dots.filter((d) => d.source.mode !== 'LIVE').map(radius);
    expect(Math.max(...inner)).toBeLessThan(Math.min(...outer));
  });

  it('splits them about evenly left and right of the title', () => {
    const left = dots.filter((d) => d.x < frame.cx).length;
    expect(Math.abs(left - (dots.length - left))).toBeLessThanOrEqual(3);
  });

  it('same order, same places, on every visit', () => {
    expect(placeDots(external, frame)).toEqual(dots);
  });
});

it('up to 900 px only the rings show (home.css); on a phone they sit at the right edge', () => {
  expect(radarFrame(390, 300, null).cx).toBe(310);
  expect(radarFrame(1280, 340, null).cx).toBe(640);
});

it("the angle of a dot is clockwise from 3 o'clock, as the beam turns", () => {
  const at = (x: number, y: number) => ({ ...placeDots(external, wide)[0]!, x, y });
  expect(angleOf(at(wide.cx + 100, wide.cy), wide)).toBe(0);
  expect(angleOf(at(wide.cx, wide.cy + 100), wide)).toBe(90);
  expect(angleOf(at(wide.cx - 100, wide.cy), wide)).toBe(180);
  expect(angleOf(at(wide.cx, wide.cy - 100), wide)).toBe(270);
});
