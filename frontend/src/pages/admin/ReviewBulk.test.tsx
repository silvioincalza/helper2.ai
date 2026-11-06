import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReviewBulkResult, ReviewItem } from '@/api/types';
import { ADMIN, makeReviewEvidence } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp } from '@/test/render';

const item = (n: number, over: Partial<ReviewItem> = {}): ReviewItem => ({
  id: `66666666-6666-5666-8666-66666666666${n}`,
  kind: 'ENRICHMENT_VALIDATION',
  status: 'OPEN',
  reason: `Item ${n}`,
  details: null,
  resourceId: null,
  resourceTitle: `Acme ${n}`,
  createdAt: '2026-11-01T10:00:00Z',
  ...over,
});
const BATCH = '99999999-9999-5999-8999-999999999999';
const box = (n: number) => screen.getByRole('checkbox', { name: `Pick: Item ${n}` });
const bar = () => screen.getByRole('group', { name: 'Bulk decision' });

/**
 * Two enrichment checks and a user report, open. The server decides what [outcomes] says for each id (RESOLVED by
 * default) and drops the resolved ones from the list.
 */
function queue(outcomes: Record<string, ReviewBulkResult['items'][number]['outcome']> = {}) {
  let items = [item(1), item(2), item(3, { kind: 'USER_REPORT' })];
  return mockApi({
    'GET /me': () => ADMIN,
    'GET /admin/review-items': () => ({ items, total: items.length }),
    'GET /admin/review-items/*': () => makeReviewEvidence(items[0] ?? item(1)),
    'POST /admin/review-items/resolutions': ({ body }) => {
      const { ids } = body as { ids: string[] };
      const results = ids.map((id) => ({ id, outcome: outcomes[id] ?? 'RESOLVED' }));
      const resolved = new Set(results.filter((r) => r.outcome === 'RESOLVED').map((r) => r.id));
      items = items.filter((i) => !resolved.has(i.id));
      return { batch: BATCH, items: results };
    },
  });
}

describe('Feature: deciding several review items at once (ADR 0045)', () => {
  it('Scenario: each open row has a box; x picks the selected item; the bar counts', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => ({
        items: [item(1), item(2, { status: 'ACCEPTED' })],
        total: 2,
      }),
      'GET /admin/review-items/*': () => makeReviewEvidence(item(1)),
    });
    renderApp('/admin/review?status=ACCEPTED');
    await screen.findByRole('region', { name: 'Item 1' });
    expect(screen.getAllByRole('checkbox')).toHaveLength(1);
    expect(screen.queryByRole('group', { name: 'Bulk decision' })).not.toBeInTheDocument();
    screen.getByRole('button', { name: 'Item 1' }).focus();
    await userEvent.keyboard('x');
    expect(box(1)).toBeChecked();
    expect(bar()).toHaveTextContent('1 selected');
    expect(box(1)).toHaveAttribute('aria-keyshortcuts', 'x');
    // x on the box itself toggles it too.
    act(() => box(1).focus());
    await userEvent.keyboard('x');
    expect(box(1)).not.toBeChecked();
  });

  it('Scenario: Accept asks first, repeats the count by kind, and Cancel leaves everything as it was', async () => {
    const m = queue();
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    await userEvent.click(box(1));
    await userEvent.click(box(2));
    await userEvent.click(box(3));
    const accept = within(bar()).getByRole('button', { name: 'Accept' });
    await userEvent.click(accept);
    const dialog = await screen.findByRole('dialog', { name: 'Accept 3 items?' });
    expect(dialog).toHaveTextContent('2 Enrichment validation · 1 User report');
    expect(dialog).toHaveTextContent('Only enrichment checks with no conflict');
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(accept).toHaveFocus());
    expect(m.calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    expect(bar()).toHaveTextContent('3 selected');
  });

  it('Scenario: confirmed, each item is decided; the one refused stays picked and the line says why', async () => {
    const m = queue({ [item(3).id]: 'NOT_ALLOWED' });
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    await userEvent.click(box(1));
    await userEvent.click(box(2));
    await userEvent.click(box(3));
    await userEvent.click(within(bar()).getByRole('button', { name: 'Accept' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Accept 3' }));
    expect(await screen.findByText('2 done, 1 failed (not allowed in bulk).')).toHaveAttribute(
      'role',
      'status',
    );
    expect(m.calls.find((c) => c.method === 'POST')?.body).toEqual({
      ids: [item(1).id, item(2).id, item(3).id],
      status: 'ACCEPTED',
    });
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Item 1' })).toBeNull());
    expect(bar()).toHaveTextContent('1 selected');
    expect(box(3)).toBeChecked();
  });

  it('Scenario: several reasons are counted', async () => {
    queue({ [item(1).id]: 'NOT_OPEN', [item(3).id]: 'NOT_ALLOWED' });
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    await userEvent.click(box(1));
    await userEvent.click(box(2));
    await userEvent.click(box(3));
    await userEvent.click(within(bar()).getByRole('button', { name: 'Accept' }));
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Accept 3' }),
    );
    expect(
      await screen.findByText('1 done, 2 failed (1 already decided, 1 not allowed in bulk).'),
    ).toBeInTheDocument();
  });

  it('Scenario: Reject with a note decides every kind; when all are done the bar goes', async () => {
    const m = queue();
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    await userEvent.click(box(1));
    await userEvent.click(box(3));
    await userEvent.click(within(bar()).getByRole('button', { name: 'Reject' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reject 2 items?' });
    expect(dialog).not.toHaveTextContent('Only enrichment checks');
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Note (optional)' }), ' dup ');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reject 2' }));
    expect(await screen.findByText('2 done.')).toBeInTheDocument();
    expect(m.calls.find((c) => c.method === 'POST')?.body).toEqual({
      ids: [item(1).id, item(3).id],
      status: 'REJECTED',
      note: 'dup',
    });
    expect(screen.queryByRole('group', { name: 'Bulk decision' })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Item 2' })).toHaveFocus());
  });

  it('Scenario: a request that fails says so and keeps the items picked', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => ({ items: [item(1)], total: 1 }),
      'GET /admin/review-items/*': () => makeReviewEvidence(item(1)),
      'POST /admin/review-items/resolutions': () => problem(500, 'Server error'),
    });
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    await userEvent.click(box(1));
    await userEvent.click(within(bar()).getByRole('button', { name: 'Reject' }));
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Reject 1' }),
    );
    expect(await screen.findByText('The action failed. Please try again.')).toBeInTheDocument();
    expect(box(1)).toBeChecked();
  });

  it('Scenario: a request that fails halfway reloads the list, so a decided item is not shown as open', async () => {
    let items = [item(1), item(2)];
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => ({ items, total: items.length }),
      'GET /admin/review-items/*': () => makeReviewEvidence(items[0] ?? item(1)),
      'POST /admin/review-items/resolutions': () => {
        items = [item(2)];
        return problem(500, 'Server error');
      },
    });
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    await userEvent.click(box(1));
    await userEvent.click(box(2));
    await userEvent.click(within(bar()).getByRole('button', { name: 'Reject' }));
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Reject 2' }),
    );
    expect(await screen.findByText('The action failed. Please try again.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Item 1' })).toBeNull());
    expect(
      m.calls.filter((c) => c.method === 'GET' && c.path === '/admin/review-items').length,
    ).toBeGreaterThan(1);
    expect(box(2)).toBeChecked();
    expect(bar()).toHaveTextContent('1 selected');
  });

  it('Scenario: a key typed on the box of another row acts on that row, not on the selected one', async () => {
    const m = queue();
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    act(() => box(2).focus());
    await userEvent.keyboard('x');
    expect(box(2)).toBeChecked();
    expect(box(1)).not.toBeChecked();
    await screen.findByRole('region', { name: 'Item 2' });
    await userEvent.keyboard('r');
    await waitFor(() => expect(m.calls.some((c) => c.method === 'PUT')).toBe(true));
    expect(m.calls.find((c) => c.method === 'PUT')?.path).toContain(item(2).id);
  });

  it('Scenario: a new filter clears the picked items; Clear does too', async () => {
    queue();
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    await userEvent.click(box(1));
    await userEvent.click(within(bar()).getByRole('button', { name: 'Clear' }));
    expect(screen.queryByRole('group', { name: 'Bulk decision' })).not.toBeInTheDocument();
    await userEvent.click(box(2));
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Kind' }), 'User report');
    expect(screen.queryByRole('group', { name: 'Bulk decision' })).not.toBeInTheDocument();
  });
});
