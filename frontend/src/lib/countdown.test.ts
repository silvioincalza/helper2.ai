import { countdown, daysUntil } from './countdown';

describe('countdown: how far away an event is, in words', () => {
  it.each([
    [0, { key: 'today' }],
    [-2, { key: 'today' }],
    [1, { key: 'tomorrow' }],
    [2, { key: 'days', count: 2 }],
    [13, { key: 'days', count: 13 }],
    [14, { key: 'weeks', count: 2 }],
    [20, { key: 'weeks', count: 3 }],
    [44, { key: 'weeks', count: 6 }],
    [45, { key: 'months', count: 2 }],
    [83, { key: 'months', count: 3 }],
  ])('%i days → %o', (days, expected) => {
    expect(countdown(days)).toEqual(expected);
  });

  it('counts calendar days in UTC, whatever the time of day', () => {
    const now = Date.parse('2026-10-30T23:30:00Z');
    expect(daysUntil('2026-10-31', now)).toBe(1);
    expect(daysUntil('2026-11-12T00:00:00Z', now)).toBe(13);
    expect(daysUntil('2026-10-30', now)).toBe(0);
  });
});
