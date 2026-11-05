import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import type { EnrichmentQueuePage } from '@/api/types';

const entry = (
  over: Partial<EnrichmentQueuePage['items'][number]> = {},
): EnrichmentQueuePage['items'][number] => ({
  resourceId: '11111111-1111-5111-8111-111111111111',
  title: 'Acme MCP',
  waiting: 'NEW',
  firstDiscoveredAt: '2026-11-04T09:00:00Z',
  failedRuns: 0,
  lastOutcome: null,
  lastRunAt: null,
  ...over,
});

describe('Feature: the enrichment queue shows what the Scout takes next', () => {
  it('Scenario: rows say why each resource waits, in words, and its runs so far', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/enrichment-queue': () => ({
        items: [
          entry(),
          entry({
            resourceId: '22222222-2222-5222-8222-222222222222',
            title: 'Flaky',
            waiting: 'RETRY',
            failedRuns: 2,
            lastOutcome: 'FAILED',
            lastRunAt: '2026-11-05T08:00:00Z',
          }),
        ],
        next: null,
      }),
    });
    renderApp('/admin/enrichment');
    const table = await screen.findByRole('table', { name: 'Enrichment queue' });
    const [fresh, flaky] = within(table).getAllByRole('row').slice(1);
    expect(within(fresh!).getByRole('link', { name: 'Acme MCP' })).toHaveAttribute(
      'href',
      '/resource/11111111-1111-5111-8111-111111111111',
    );
    expect(within(fresh!).getByText('Not run yet')).toHaveClass('h2-status-pill--next');
    expect(within(fresh!).getByText('None')).toBeInTheDocument();
    expect(within(flaky!).getByText('Retry after a failure')).toHaveClass('h2-status-pill--warn');
    expect(within(flaky!).getByText(/^FAILED, /)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Next page' })).not.toBeInTheDocument();
    expect(
      within(screen.getByRole('navigation', { name: 'Back office' })).getByRole('link', {
        name: 'Enrichment',
      }),
    ).toHaveAttribute('aria-current', 'page');
  });

  it('Scenario: Next page follows the server cursor in the address, and First page goes back', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/enrichment-queue': ({ url }) =>
        url.searchParams.get('after') === 'c2'
          ? { items: [entry({ title: 'Second page' })], next: null }
          : { items: [entry({ title: 'First page item' })], next: 'c2' },
    });
    renderApp('/admin/enrichment');
    await screen.findByText('First page item');
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('Second page')).toBeInTheDocument();
    expect(m.callsTo('GET', '/admin/enrichment-queue').at(-1)?.search).toBe('?after=c2&size=20');
    await userEvent.click(screen.getByRole('button', { name: 'First page' }));
    expect(await screen.findByText('First page item')).toBeInTheDocument();
  });

  it('Scenario: an empty queue says so, and a failed load offers Try again', async () => {
    let fail = true;
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/enrichment-queue': () =>
        fail ? problem(503, 'Service Unavailable') : { items: [], next: null },
    });
    renderApp('/admin/enrichment');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the enrichment queue.');
    fail = false;
    await userEvent.click(within(alert).getByRole('button', { name: /Try again/ }));
    expect(await screen.findByText('Nothing waits for enrichment.')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Enrichment queue' })).toHaveFocus(),
    );
  });
});
