import i18n from '@/i18n';
import { durationParts, formatDuration } from './duration';

const HOUR = 3600;
const DAY = 24 * HOUR;
const say = (seconds: number) => formatDuration(seconds, i18n.t);

describe('formatDuration', () => {
  it.each([
    [DAY, '24 hours'],
    [7 * DAY, '7 days'],
    [730 * DAY, '24 months'],
    [365 * DAY, '12 months'],
    [180 * DAY, '180 days'],
    [90 * DAY, '90 days'],
    [2 * DAY, '2 days'],
    [36 * HOUR, '36 hours'],
    [HOUR, '1 hour'],
    [30 * 60, '30 minutes'],
    [60, '1 minute'],
    [45, '45 seconds'],
    [0, '0 seconds'],
  ])('%i seconds read as "%s"', (seconds, words) => expect(say(seconds)).toBe(words));

  it('never says less than the period: a part of a day stays in hours, a part of a month in days', () => {
    expect(say(3 * DAY + HOUR)).toBe('73 hours');
    expect(say(400 * DAY)).toBe('400 days');
    expect(say(DAY + 30 * 60)).toBe('1470 minutes');
  });

  it('a period of a year or more reads in months only near a whole month', () => {
    expect(durationParts(548 * DAY)).toEqual({ unit: 'months', count: 18 });
    expect(durationParts(380 * DAY)).toEqual({ unit: 'days', count: 380 });
  });
});
