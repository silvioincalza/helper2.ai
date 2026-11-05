import { makeStatus } from '@/test/fixtures';
import { missedSources, retryCanHelp } from './coverage';

const skipped = (sourceId: string, skipReason: Parameters<typeof makeStatus>[0] = {}) =>
  makeStatus({ sourceId, name: sourceId, outcome: 'SKIPPED', servedFrom: 'NONE', ...skipReason });

describe('missedSources', () => {
  it('lists the sources coverage counts as queried that did not answer, with why', () => {
    const missed = missedSources([
      makeStatus(),
      makeStatus({ sourceId: 't', name: 't', outcome: 'TIMED_OUT', servedFrom: 'CACHE' }),
      skipped('paused', { skipReason: 'RATE_LIMIT_PAUSE' }),
      skipped('open', { skipReason: 'CIRCUIT_OPEN' }),
      skipped('busy', { skipReason: 'BUSY' }),
      skipped('quota', { skipReason: 'QUOTA_UNAVAILABLE' }),
    ]);
    expect(missed.map((m) => [m.sourceId, m.reason, m.fromCache])).toEqual([
      ['t', 'TIMED_OUT', true],
      ['paused', 'RATE_LIMIT_PAUSE', false],
      ['open', 'CIRCUIT_OPEN', false],
      ['busy', 'BUSY', false],
      ['quota', 'QUOTA_UNAVAILABLE', false],
    ]);
  });

  it('leaves out sources skipped on purpose, as coverage does', () => {
    expect(
      missedSources([
        skipped('off', { skipReason: 'DISABLED' }),
        skipped('web', { skipReason: 'WEB_NOT_ASKED' }),
        skipped('limit', { skipReason: 'DAILY_LIMIT' }),
        skipped('browse', { skipReason: 'NOT_BROWSABLE' }),
      ]),
    ).toEqual([]);
  });

  it('keeps a skipped source served from cache, even without a reason', () => {
    expect(missedSources([skipped('old', { servedFrom: 'STALE_CACHE' })])).toEqual([
      { sourceId: 'old', name: 'old', reason: 'SKIPPED', fromCache: true },
    ]);
  });
});

describe('retryCanHelp', () => {
  const one = (s: Parameters<typeof makeStatus>[0]) => missedSources([makeStatus(s)]);

  it.each(['FAILED', 'TIMED_OUT'] as const)('yes after %s', (outcome) => {
    expect(retryCanHelp(one({ outcome, servedFrom: 'NONE' }))).toBe(true);
  });

  it('yes when a source was busy', () => {
    expect(retryCanHelp(missedSources([skipped('b', { skipReason: 'BUSY' })]))).toBe(true);
  });

  it('no after a rate limit, a pause, an open circuit or an unreadable quota', () => {
    expect(
      retryCanHelp([
        ...one({ outcome: 'RATE_LIMITED', servedFrom: 'NONE' }),
        ...missedSources([
          skipped('p', { skipReason: 'RATE_LIMIT_PAUSE' }),
          skipped('o', { skipReason: 'CIRCUIT_OPEN' }),
          skipped('q', { skipReason: 'QUOTA_UNAVAILABLE' }),
        ]),
      ]),
    ).toBe(false);
  });

  it('no when nothing was missed', () => {
    expect(retryCanHelp([])).toBe(false);
  });
});
