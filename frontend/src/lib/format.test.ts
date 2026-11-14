import {
  backAgain,
  clockTime,
  dateLocale,
  displayHost,
  formatDate,
  formatMonth,
  localDay,
  longLocalDay,
  relativeTime,
  shortDay,
  shortMonth,
  weekDay,
} from './format';

const browser = (language: string) =>
  vi.spyOn(window.navigator, 'language', 'get').mockReturnValue(language);

const zone = (timeZone: string) => vi.stubEnv('TZ', timeZone);

describe('displayHost', () => {
  it('drops www.', () => expect(displayHost('https://www.acme.example/x')).toBe('acme.example'));
  it('keeps text that is not a URL', () => expect(displayHost('not a url')).toBe('not a url'));
});

describe('dateLocale: the words of the app language, the conventions of the browser region', () => {
  it.each([
    ['it-CH', 'en-CH'],
    ['en-US', 'en-US'],
    ['de-DE', 'en-DE'],
    ['ja-JP', 'en-JP'],
    ['zh-Hant-TW', 'en-TW'],
  ])('a browser in %s reads an English page as %s', (language, locale) => {
    browser(language);

    expect(dateLocale('en')).toBe(locale);
  });

  it.each([['it'], [''], ['not a locale!'], ['x']])(
    'a browser language without a region (%j) leaves the app language alone',
    (language) => {
      browser(language);

      expect(dateLocale('en')).toBe('en');
    },
  );

  it('another app language keeps its own words', () => {
    browser('en-US');

    expect(dateLocale('it')).toBe('it-US');
  });

  it('without an argument it reads the app language', () => {
    browser('de-CH');

    expect(dateLocale()).toBe('en-CH');
  });
});

describe('an INSTANT is shown in the time zone and the clock of the visitor', () => {
  const instant = '2026-11-11T13:30:05Z';

  it.each([
    ['it-CH', 'Europe/Zurich', '11 Nov 2026, 14:30'],
    ['de-DE', 'Europe/Zurich', '11 Nov 2026, 14:30'],
    ['ja-JP', 'Asia/Tokyo', 'Nov 11, 2026, 22:30'],
    ['en-US', 'America/Los_Angeles', 'Nov 11, 2026, 5:30 AM'],
    ['', 'America/Los_Angeles', 'Nov 11, 2026, 5:30 AM'],
    ['not a locale!', 'Europe/Zurich', 'Nov 11, 2026, 2:30 PM'],
  ])('browser %j in %s', (language, timeZone, shown) => {
    browser(language);
    zone(timeZone);

    expect(formatDate(instant, 'INSTANT').replace(/\s/g, ' ')).toBe(shown);
  });

  it.each([
    ['it-CH', 'Europe/Zurich', '14:30:05', '14:30'],
    ['de-DE', 'Europe/Zurich', '14:30:05', '14:30'],
    ['ja-JP', 'Asia/Tokyo', '22:30:05', '22:30'],
    ['en-US', 'America/Los_Angeles', '5:30:05 AM', '5:30 AM'],
  ])('a clock time, browser %s in %s', (language, timeZone, withSeconds, short) => {
    browser(language);
    zone(timeZone);

    expect(clockTime(instant).replace(/\s/g, ' ')).toBe(withSeconds);
    expect(clockTime(instant, false).replace(/\s/g, ' ')).toBe(short);
  });

  it('midnight UTC is 01:00 in Zurich, 4:00 PM the day before in Los Angeles', () => {
    browser('it-CH');
    zone('Europe/Zurich');
    expect(clockTime('2026-11-12T00:00:00Z', false)).toBe('01:00');

    browser('en-US');
    zone('America/Los_Angeles');
    expect(clockTime('2026-11-12T00:00:00Z', false).replace(/\s/g, ' ')).toBe('4:00 PM');
  });

  it('the day of an instant is the day of the visitor', () => {
    browser('ja-JP');
    zone('Asia/Tokyo');
    expect(localDay('2026-11-11T23:30:00Z')).toBe('Nov 12, 2026');
    expect(longLocalDay(Date.parse('2026-11-11T23:30:00Z'))).toBe('Thursday, November 12, 2026');

    browser('en-US');
    zone('America/Los_Angeles');
    expect(localDay('2026-11-11T02:30:00Z')).toBe('Nov 10, 2026');
    expect(longLocalDay(Date.parse('2026-11-11T02:30:00Z'))).toBe('Tuesday, November 10, 2026');
  });

  it('a value that cannot be read is shown as it is', () => {
    expect(formatDate('nope', 'INSTANT')).toBe('nope');
    expect(clockTime('nope')).toBe('nope');
    expect(localDay('nope')).toBe('nope');
    expect(longLocalDay(Number.NaN)).toBe('');
  });
});

describe('a DAY fact is a calendar day: no time zone moves it', () => {
  it.each([
    ['en-US', 'America/Los_Angeles', 'Nov 11, 2026', 'Nov 11', '11 Wed'],
    ['ja-JP', 'Asia/Tokyo', 'Nov 11, 2026', 'Nov 11', '11 Wed'],
    ['it-CH', 'Europe/Zurich', '11 Nov 2026', '11 Nov', 'Wed 11'],
  ])('browser %s in %s', (language, timeZone, medium, short, week) => {
    browser(language);
    zone(timeZone);

    for (const day of ['2026-11-11T00:00:00Z', '2026-11-11T00:30:00Z', '2026-11-11T23:30:00Z']) {
      expect(formatDate(day, 'DAY')).toBe(medium);
      expect(formatDate(day)).toBe(medium);
      expect(shortDay(day)).toBe(short);
      expect(weekDay(day)).toBe(week);
      expect(shortMonth(day)).toBe('Nov 2026');
    }
    expect(formatMonth('2026-11')).toBe('November 2026');
  });

  it('the first and the last day of a month stay in their month', () => {
    browser('en-US');
    zone('America/Los_Angeles');
    expect(shortMonth('2026-11-01T00:00:00Z')).toBe('Nov 2026');

    browser('ja-JP');
    zone('Asia/Tokyo');
    expect(shortMonth('2026-11-30T23:00:00Z')).toBe('Nov 2026');
    expect(formatMonth('2026-12')).toBe('December 2026');
  });
});

describe('the words stay in the app language', () => {
  it.each([['it-CH'], ['de-DE'], ['ja-JP'], ['en-US']])('browser %s', (language) => {
    browser(language);

    expect(formatMonth('2026-10')).toBe('October 2026');
    expect(weekDay('2026-10-05')).toMatch(/Mon/);
    expect(relativeTime().format(-2, 'day')).toBe('2 days ago');
    expect(relativeTime().format(-1, 'day')).toBe('yesterday');
    expect(relativeTime('short').format(-3, 'hour')).toMatch(/^3 hr\.? ago$/);
  });
});

describe('shortDay', () => {
  const now = Date.parse('2026-11-05T09:00:00Z');
  it('day and month in the same year', () =>
    expect(shortDay('2026-11-20T00:00:00Z', now)).toBe('Nov 20'));
  it('adds the year when it is another year', () =>
    expect(shortDay('2027-01-12T00:00:00Z', now)).toBe('Jan 12, 2027'));
  it('reads the year as a UTC calendar day', () =>
    expect(shortDay('2026-12-31T23:30:00Z', Date.parse('2027-01-01T00:10:00Z'))).toBe(
      'Dec 31, 2026',
    ));
  it('without a clock, day and month only', () =>
    expect(shortDay('2027-01-12T00:00:00Z')).toBe('Jan 12'));
  it('empty for a date it cannot read', () => expect(shortDay('nope', now)).toBe(''));
  it('a month it cannot read is shown as it is', () => {
    expect(formatMonth('nope')).toBe('nope');
    expect(shortMonth('nope')).toBe('');
  });
});

describe('backAgain', () => {
  const now = Date.parse('2026-11-09T12:30:00Z');

  it('reads the time zone of the browser when none is given', () => {
    browser('it-CH');
    zone('Europe/Zurich');
    expect(backAgain('x', '2026-11-10T00:00:00Z', { now })).toEqual({
      key: 'xBackTomorrowAt',
      time: '01:00',
    });

    browser('en-US');
    zone('America/Los_Angeles');
    expect(backAgain('x', '2026-11-10T00:00:00Z', { now })).toEqual({
      key: 'xBackAt',
      time: expect.stringMatching(/^4:00\sPM$/),
    });
  });
});
