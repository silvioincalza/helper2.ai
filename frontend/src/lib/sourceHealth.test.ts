import type { SourceInfo } from '@/api/types';
import {
  clockTime,
  creditsOf,
  pauseOf,
  responseTimeLabel,
  responseTimeShown,
} from './sourceHealth';

const source = (over: Partial<SourceInfo> = {}): SourceInfo => ({
  id: 'github',
  name: 'GitHub',
  mode: 'LIVE',
  enabled: true,
  metered: false,
  homepage: 'https://github.com',
  resourceTypes: [],
  ...over,
});

describe('responseTimeLabel', () => {
  it('under a second: milliseconds, short and long', () => {
    expect(responseTimeLabel(240, 'en')).toEqual({ short: '240 ms', long: '240 milliseconds' });
  });

  it('from a second: seconds with one decimal', () => {
    expect(responseTimeLabel(1250, 'en')).toEqual({ short: '1.3 sec', long: '1.3 seconds' });
    expect(responseTimeLabel(1000, 'en')).toEqual({ short: '1 sec', long: '1 second' });
  });
});

describe('pauseOf', () => {
  it('OPEN is a pause since the time the circuit opened', () => {
    expect(pauseOf(source({ circuit: 'OPEN', circuitOpenedAt: '2026-11-04T09:14:05Z' }))).toEqual({
      kind: 'paused',
      since: '2026-11-04T09:14:05Z',
    });
    expect(pauseOf(source({ circuit: 'OPEN', circuitOpenedAt: null }))).toEqual({
      kind: 'paused',
      since: null,
    });
  });

  it('HALF_OPEN is trying again; CLOSED, null or no field is no pause', () => {
    expect(pauseOf(source({ circuit: 'HALF_OPEN' }))).toEqual({ kind: 'trying' });
    expect(pauseOf(source({ circuit: 'CLOSED' }))).toBeNull();
    expect(pauseOf(source({ circuit: null }))).toBeNull();
    expect(pauseOf(source())).toBeNull();
  });

  it('a disabled source is never paused', () => {
    expect(pauseOf(source({ enabled: false, circuit: 'OPEN' }))).toBeNull();
  });
});

describe('creditsOf', () => {
  const tavily = (over: Partial<SourceInfo> = {}) =>
    source({ id: 'tavily', metered: true, ...over });

  it('with a cap, without a cap, or not readable, on the enabled metered source', () => {
    expect(creditsOf(tavily({ webCredits: { usedToday: 312, dailyCap: 1000 } }))).toEqual({
      kind: 'capped',
      used: 312,
      cap: 1000,
    });
    expect(creditsOf(tavily({ webCredits: { usedToday: 312, dailyCap: null } }))).toEqual({
      kind: 'uncapped',
      used: 312,
    });
    expect(creditsOf(tavily({ webCredits: null }))).toEqual({ kind: 'unavailable' });
    expect(creditsOf(tavily())).toEqual({ kind: 'unavailable' });
  });

  it('nothing on other sources or on a disabled metered source', () => {
    expect(creditsOf(source({ webCredits: null }))).toBeNull();
    expect(creditsOf(tavily({ enabled: false }))).toBeNull();
  });

  it('the API says which source is metered, not its id', () => {
    expect(creditsOf(source({ id: 'other-web', metered: true, webCredits: null }))).toEqual({
      kind: 'unavailable',
    });
    expect(creditsOf(tavily({ metered: false, webCredits: null }))).toBeNull();
  });

  it('an answer without the field (a replica not yet updated) counts as not metered', () => {
    const { metered: _, ...old } = tavily({ webCredits: null });
    expect(creditsOf(old as SourceInfo)).toBeNull();
  });
});

describe('clockTime', () => {
  it('a UTC clock time, with or without seconds', () => {
    expect(clockTime('2026-11-04T09:14:05Z', 'en')).toBe('09:14:05 UTC');
    expect(clockTime('2026-11-04T21:04:05Z', 'en', false)).toBe('21:04 UTC');
  });
});

describe('responseTimeShown', () => {
  it('a time is always shown; without one, only an enabled live source says "No calls yet"', () => {
    expect(responseTimeShown(source({ mode: 'CURATED', responseTimeMs: 12 }))).toBe(true);
    expect(responseTimeShown(source({ responseTimeMs: null }))).toBe(true);
    expect(responseTimeShown(source({ mode: 'CURATED', responseTimeMs: null }))).toBe(false);
    expect(responseTimeShown(source({ enabled: false }))).toBe(false);
  });
});
