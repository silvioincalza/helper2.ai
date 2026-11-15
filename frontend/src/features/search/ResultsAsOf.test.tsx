import { render } from '@testing-library/react';
import { makeStatus } from '@/test/fixtures';
import { clockTime } from '@/lib/format';
import { ResultsAsOf } from './ResultsAsOf';

describe('the time of the results next to the result count', () => {
  it('shows the oldest time a source served from the cache was saved', () => {
    const { container } = render(
      <ResultsAsOf
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

  it('shows no time when every source answered live', () => {
    const { container } = render(
      <ResultsAsOf
        sources={[makeStatus({ sourceId: 'a' }), makeStatus({ sourceId: 'b', latencyMs: 40 })]}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
