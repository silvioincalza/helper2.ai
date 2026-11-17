import { screen, within } from '@testing-library/react';
import { ADMIN } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';

const overview = (validationIssues?: unknown) => ({
  generatedAt: '2026-11-17T22:00:00Z',
  openReviewItems: [],
  enrichmentBacklog: 0,
  budgets: [],
  recentRuns: [],
  failedRunsLast24h: 0,
  homeExamples: { shown: [], hidden: [], paused: 0 },
  overridesActive: 0,
  ...(validationIssues ? { validationIssues } : {}),
});

describe('Feature: the Overview groups the enrichment validation issues (S6.2.14)', () => {
  it('Scenario: the most frequent issues of the last 7 days, with how many runs had each', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/overview': () =>
        overview({
          days: 7,
          runs: 1000,
          top: [
            {
              subject: 'field pricing',
              rule: 'the cited excerpt does not state the value',
              runs: 812,
            },
            { subject: 'field platforms', rule: 'excerpt length N outside N..N', runs: 240 },
          ],
        }),
    });
    renderAdmin('/admin');
    const table = await screen.findByRole('table', {
      name: 'Enrichment validation issues: 1000 runs in the last 7 days',
    });
    const first = within(table).getAllByRole('row')[1] as HTMLElement;
    expect(first).toHaveTextContent('field pricing');
    expect(first).toHaveTextContent('the cited excerpt does not state the value');
    expect(first).toHaveTextContent('812');
  });

  it('Scenario: an answer without the counts shows no issue section', async () => {
    mockApi({ 'GET /me': () => ADMIN, 'GET /admin/overview': () => overview() });
    renderAdmin('/admin');
    expect(await screen.findByText(/Refresh/)).toBeInTheDocument();
    expect(screen.queryByText(/Enrichment validation issues/)).not.toBeInTheDocument();
  });
});
