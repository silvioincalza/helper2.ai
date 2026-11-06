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
  state: 'WAITING',
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
      '/resource/11111111-1111-5111-8111-111111111111/acme-mcp',
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
    await screen.findByRole('link', { name: 'First page item' });
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByRole('link', { name: 'Second page' })).toBeInTheDocument();
    expect(m.callsTo('GET', '/admin/enrichment-queue').at(-1)?.search).toBe('?after=c2&size=20');
    await userEvent.click(screen.getByRole('button', { name: 'First page' }));
    expect(await screen.findByRole('link', { name: 'First page item' })).toBeInTheDocument();
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

const ACME = '/admin/enrichment-queue/11111111-1111-5111-8111-111111111111';

describe('Feature: the admin changes the order of the enrichment queue', () => {
  it('Scenario: Move to top puts a waiting resource first, says so, and reloads the queue', async () => {
    let top = false;
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/enrichment-queue': () => ({
        items: [entry(top ? { state: 'PRIORITISED' } : {})],
        next: null,
      }),
      [`POST ${ACME}/prioritise`]: () => {
        top = true;
      },
    });
    renderApp('/admin/enrichment');
    await userEvent.click(await screen.findByRole('button', { name: 'Move to top Acme MCP' }));

    const done = await screen.findByText(/^“Acme MCP” is at the top\./);
    expect(done).toHaveAttribute('role', 'status');
    await waitFor(() => expect(done).toHaveFocus());
    expect(m.callsTo('POST', `${ACME}/prioritise`)).toHaveLength(1);
    expect(await screen.findByText('At the top')).toHaveClass('h2-status-pill--ok');
    expect(screen.queryByRole('button', { name: 'Move to top Acme MCP' })).not.toBeInTheDocument();
  });

  it('Scenario: Skip asks first, takes an optional reason, and Cancel gives focus back', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/enrichment-queue': () => ({ items: [entry()], next: null }),
      [`POST ${ACME}/skip`]: () => undefined,
    });
    renderApp('/admin/enrichment');
    const skip = await screen.findByRole('button', { name: 'Skip Acme MCP' });

    await userEvent.click(skip);
    expect(screen.getByRole('textbox', { name: 'Reason (optional)' })).toHaveFocus();
    expect(screen.getByRole('textbox', { name: 'Reason (optional)' })).toHaveAccessibleDescription(
      'No personal data',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(m.callsTo('POST', `${ACME}/skip`)).toHaveLength(0);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Skip Acme MCP' })).toHaveFocus(),
    );

    await userEvent.click(screen.getByRole('button', { name: 'Skip Acme MCP' }));
    await userEvent.type(screen.getByRole('textbox', { name: 'Reason (optional)' }), 'Not AI');
    await userEvent.click(screen.getByRole('button', { name: 'Skip' }));

    expect(await screen.findByText(/is skipped\. Find it under Skipped/)).toBeInTheDocument();
    expect(m.callsTo('POST', `${ACME}/skip`).map((c) => c.body)).toEqual([{ reason: 'Not AI' }]);
  });

  it('Scenario: the Skipped view lists the skips with their reason, and Requeue undoes one', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/enrichment-queue': ({ url }) =>
        url.searchParams.get('view') === 'SKIPPED'
          ? {
              items: [
                entry({
                  state: 'SKIPPED',
                  waiting: null,
                  skippedAt: '2026-11-05T09:00:00Z',
                  skipReason: 'Not AI',
                }),
              ],
              next: null,
            }
          : { items: [], next: null },
      [`POST ${ACME}/requeue`]: () => undefined,
    });
    renderApp('/admin/enrichment');
    await screen.findByText('Nothing waits for enrichment.');

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Show' }), 'Skipped');
    expect(await screen.findByText('Not AI')).toBeInTheDocument();
    expect(screen.getByText('Skipped', { selector: '.h2-status-pill' })).toBeInTheDocument();
    expect(m.callsTo('GET', '/admin/enrichment-queue').at(-1)?.search).toBe(
      '?view=SKIPPED&size=20',
    );
    expect(screen.queryByRole('button', { name: /^Skip / })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Requeue Acme MCP' }));
    expect(
      await screen.findByText('“Acme MCP” is back in the queue, in its own place.'),
    ).toHaveFocus();
    expect(m.callsTo('POST', `${ACME}/requeue`)).toHaveLength(1);
  });

  it('Scenario: an action that no longer applies says why, and the queue reloads', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/enrichment-queue': () => ({ items: [entry()], next: null }),
      [`POST ${ACME}/prioritise`]: () =>
        problem(409, 'Not applicable', '50 resources already wait at the top.'),
    });
    renderApp('/admin/enrichment');
    await userEvent.click(await screen.findByRole('button', { name: 'Move to top Acme MCP' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Nothing changed: 50 resources already wait at the top.');
    await waitFor(() => expect(alert).toHaveFocus());
    await waitFor(() => expect(m.callsTo('GET', '/admin/enrichment-queue')).toHaveLength(2));
  });
});
