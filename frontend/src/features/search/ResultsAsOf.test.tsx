import { render } from '@testing-library/react';
import { makeStatus } from '@/test/fixtures';
import { clockTime, dateLocale } from '@/lib/format';
import { ResultsAsOf } from './ResultsAsOf';

const searchedAt = Date.parse('2026-10-22T08:50:00Z');

describe('the time of the results next to the result count', () => {
  it('shows the oldest time a source served from the cache was saved', () => {
    const { container } = render(
      <ResultsAsOf
        now={searchedAt}
        sources={[
          makeStatus({ sourceId: 'live' }),
          makeStatus({ sourceId: 'a', servedFrom: 'CACHE', cachedAt: '2026-10-22T08:40:00Z' }),
          makeStatus({
            sourceId: 'b',
            servedFrom: 'STALE_CACHE',
            cachedAt: '2026-10-22T06:15:00Z',
          }),
        ]}
      />,
    );
    expect(container).toHaveTextContent(
      `results as of ${clockTime('2026-10-22T06:15:00Z', false)}`,
    );
  });

  it('adds the day when the oldest cached source was saved on an earlier day', () => {
    const { container } = render(
      <ResultsAsOf
        now={searchedAt}
        sources={[
          makeStatus({ sourceId: 'a', servedFrom: 'CACHE', cachedAt: '2026-10-22T08:40:00Z' }),
          makeStatus({
            sourceId: 'web',
            servedFrom: 'STALE_CACHE',
            cachedAt: '2026-10-19T18:00:00Z',
          }),
        ]}
      />,
    );
    expect(dateLocale()).toBe('en-US');
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('UTC');
    expect(container.textContent?.replace(/\s/g, ' ')).toContain('results as of Oct 19, 6:00 PM');
    expect(container.querySelector('time')).toHaveAttribute('dateTime', '2026-10-19T18:00:00Z');
  });

  it('shows no time when every source answered live', () => {
    const { container } = render(
      <ResultsAsOf
        now={searchedAt}
        sources={[makeStatus({ sourceId: 'a' }), makeStatus({ sourceId: 'b', latencyMs: 40 })]}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
