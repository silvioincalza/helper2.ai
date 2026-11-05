import { displayHost, shortDay } from './format';
describe('displayHost', () => {
  it('drops www.', () => expect(displayHost('https://www.acme.example/x')).toBe('acme.example'));
  it('keeps text that is not a URL', () => expect(displayHost('not a url')).toBe('not a url'));
});

describe('shortDay', () => {
  const now = Date.parse('2026-11-05T09:00:00Z');
  it('day and month in the same year', () =>
    expect(shortDay('2026-11-20T00:00:00Z', 'en', now)).toBe('Nov 20'));
  it('adds the year when it is another year', () =>
    expect(shortDay('2027-01-12T00:00:00Z', 'en', now)).toBe('Jan 12, 2027'));
  it('reads the year as a UTC calendar day', () =>
    expect(shortDay('2026-12-31T23:30:00Z', 'en', Date.parse('2027-01-01T00:10:00Z'))).toBe(
      'Dec 31, 2026',
    ));
  it('without a clock, day and month only', () =>
    expect(shortDay('2027-01-12T00:00:00Z', 'en')).toBe('Jan 12'));
  it('empty for a date it cannot read', () => expect(shortDay('nope', 'en', now)).toBe(''));
});
