import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReviewItem } from '@/api/types';
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
const page = (items: ReviewItem[]) => ({ items, total: items.length });
const evidencePath = (i: ReviewItem) => `/admin/review-items/${i.id}/evidence`;
const rowButton = (name: string) => screen.getByRole('button', { name });

/** Three open items; the evidence of each says which item it is in its model name. */
function threeItems(extra: Parameters<typeof mockApi>[0] = {}) {
  const items = [item(1), item(2), item(3)];
  return mockApi({
    'GET /me': () => ADMIN,
    'GET /admin/review-items': () => page(items),
    'GET /admin/review-items/*': ({ url }) => {
      const i = items.find((x) => url.pathname.includes(x.id)) ?? items[0]!;
      return makeReviewEvidence(i);
    },
    ...extra,
  });
}

describe('Feature: the evidence next to the selected item (ADR 0045)', () => {
  it('Scenario: the first item is selected and shows its run, the refused values and the conflict first', async () => {
    threeItems();
    renderApp('/admin/review');
    const detail = await screen.findByRole('region', { name: 'Item 1' });
    expect(rowButton('Item 1')).toHaveAttribute('aria-current', 'true');
    const evidence = await within(detail).findByRole('region', { name: 'Evidence' });
    expect(evidence).toHaveTextContent('nvidia/nemotron-test');
    expect(evidence).toHaveTextContent('enrich-v9');
    expect(evidence).toHaveTextContent('Partly validated');
    expect(evidence).toHaveTextContent('github-readme:acme/tool');
    expect(evidence).toHaveTextContent('pricing: the quotes disagree');
    const fields = within(evidence)
      .getAllByRole('listitem')
      .filter((li) => li.closest('ul')?.getAttribute('aria-label') === 'Fields of this run');
    expect(fields[0]).toHaveTextContent('Pricing');
    expect(fields[0]).toHaveTextContent('Conflicting');
    expect(fields[0]).toHaveTextContent('Free for everyone.');
    expect(fields[1]).toHaveTextContent('MIT');
    expect(fields[1]).toHaveTextContent('No quote.');
  });

  it('Scenario: values a model wrote are labelled and marked as AI; quotes are not', async () => {
    threeItems();
    renderApp('/admin/review');
    const evidence = await screen.findByRole('region', { name: 'Evidence' });
    const value = await within(evidence).findByText('FREE');
    expect(value).toHaveAttribute('data-ai-generated', 'true');
    expect(value).toHaveAttribute('data-ai-model', 'nvidia/nemotron-test');
    expect(within(evidence).getAllByText('Extracted by AI')).toHaveLength(2);
    const quote = within(evidence).getByText('Free for everyone.');
    expect(quote.closest('[data-ai-generated]')).toBeNull();
  });

  it('Scenario: a source link opens only for http(s), safely; any other address stays text', async () => {
    threeItems();
    renderApp('/admin/review');
    const evidence = await screen.findByRole('region', { name: 'Evidence' });
    const link = await within(evidence).findByRole('link', { name: 'github' });
    expect(link).toHaveAttribute('href', 'https://acme.example/pricing');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(within(evidence).getByText(/javascript:alert\(1\)/)).toBeInTheDocument();
    expect(within(evidence).getAllByRole('link')).toHaveLength(1);
  });

  it('Scenario: a relationship candidate shows the relationship, labelled as AI', async () => {
    const candidate = item(1, { kind: 'RELATIONSHIP_CANDIDATE' });
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page([candidate]),
      'GET /admin/review-items/*': () =>
        makeReviewEvidence(candidate, {
          fields: [],
          validationErrors: [],
          relationship: {
            kind: 'INTEGRATES_WITH',
            target: 'LangChain',
            targetId: null,
            verification: 'UNVERIFIED',
            origin: 'SELF_DECLARED',
            quotes: [],
          },
        }),
    });
    renderApp('/admin/review');
    const evidence = await screen.findByRole('region', { name: 'Evidence' });
    const claim = await within(evidence).findByText('Integrates with LangChain');
    expect(claim).toHaveAttribute('data-ai-generated', 'true');
    expect(evidence).toHaveTextContent('Self-declared by the vendor (extracted by AI)');
    expect(evidence).toHaveTextContent('Unverified');
  });

  it('Scenario: while the evidence loads it says so; an item with no run says there is none', async () => {
    let answer: (() => void) | undefined;
    const report = item(1, { kind: 'USER_REPORT' });
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page([report]),
      'GET /admin/review-items/*': () =>
        new Promise((r) => {
          answer = () =>
            r(
              makeReviewEvidence(report, {
                run: null,
                fields: [],
                validationErrors: [],
                aiContent: null,
              }),
            );
        }),
    });
    renderApp('/admin/review');
    expect(await screen.findByText('Loading the evidence…')).toBeInTheDocument();
    // The request reaches the server a few ticks after the panel shows it loads.
    await waitFor(() => expect(answer).toBeDefined());
    answer!();
    expect(
      await screen.findByText(
        'No run or relationship behind this item. Decide from what it says above.',
      ),
    ).toBeInTheDocument();
  });

  it('Scenario: evidence that cannot load says so, and Try again shows it', async () => {
    let fail = true;
    const one = item(1);
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page([one]),
      'GET /admin/review-items/*': () =>
        fail ? problem(500, 'Server error') : makeReviewEvidence(one),
    });
    renderApp('/admin/review');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the evidence.');
    fail = false;
    await userEvent.click(within(alert).getByRole('button', { name: /Try again/ }));
    expect(await screen.findByText('Free for everyone.')).toBeInTheDocument();
  });

  it('Scenario: clicking a row selects it and shows its evidence', async () => {
    const m = threeItems();
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    await userEvent.click(rowButton('Item 2'));
    expect(await screen.findByRole('region', { name: 'Item 2' })).toBeInTheDocument();
    expect(rowButton('Item 2')).toHaveAttribute('aria-current', 'true');
    expect(rowButton('Item 1')).not.toHaveAttribute('aria-current');
    await waitFor(() => expect(m.callsTo('GET', evidencePath(item(2)))).toHaveLength(1));
  });
});

describe('Feature: reviewing with the keyboard (WCAG 2.1.4: keys only inside the queue)', () => {
  it('Scenario: j and k move the selection and the focus', async () => {
    threeItems();
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    rowButton('Item 1').focus();
    await userEvent.keyboard('j');
    expect(rowButton('Item 2')).toHaveFocus();
    expect(rowButton('Item 2')).toHaveAttribute('aria-current', 'true');
    expect(await screen.findByRole('region', { name: 'Item 2' })).toBeInTheDocument();
    await userEvent.keyboard('jj');
    expect(rowButton('Item 3')).toHaveFocus();
    await userEvent.keyboard('k');
    expect(rowButton('Item 2')).toHaveFocus();
  });

  it('Scenario: a accepts the selected item; the next one takes focus and the status says what is left', async () => {
    let items = [item(1), item(2), item(3)];
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page(items),
      'GET /admin/review-items/*': () => makeReviewEvidence(items[0]!),
      'PUT /admin/review-items/*': ({ url }) => {
        items = items.filter((i) => !url.pathname.includes(i.id));
        return { ...item(2), status: 'ACCEPTED' };
      },
    });
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    await userEvent.click(rowButton('Item 2'));
    await userEvent.keyboard('a');
    await waitFor(() => expect(rowButton('Item 3')).toHaveFocus());
    expect(rowButton('Item 3')).toHaveAttribute('aria-current', 'true');
    const put = m.calls.find((c) => c.method === 'PUT');
    expect(put?.path).toBe(`/admin/review-items/${item(2).id}/resolution`);
    expect(put?.body).toEqual({ status: 'ACCEPTED' });
    expect(screen.getByText('Accepted. 2 left.')).toBeInTheDocument();
  });

  it('Scenario: r rejects with the note; n goes to the note, where keys are text, and Escape goes back', async () => {
    const m = threeItems({
      'PUT /admin/review-items/*': () => ({ ...item(1), status: 'REJECTED' }),
    });
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    rowButton('Item 1').focus();
    await userEvent.keyboard('n');
    const note = screen.getByRole('textbox', { name: 'Note (optional)' });
    expect(note).toHaveFocus();
    await userEvent.keyboard('a jar');
    expect(note).toHaveValue('a jar');
    expect(m.calls.filter((c) => c.method === 'PUT')).toHaveLength(0);
    await userEvent.keyboard('{Escape}');
    expect(rowButton('Item 1')).toHaveFocus();
    await userEvent.keyboard('r');
    await waitFor(() =>
      expect(m.calls.find((c) => c.method === 'PUT')?.body).toEqual({
        status: 'REJECTED',
        note: 'a jar',
      }),
    );
    expect(await screen.findByText('Rejected. 2 left.')).toBeInTheDocument();
  });

  it('Scenario: keys outside the queue do nothing', async () => {
    const m = threeItems();
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    screen.getByRole('combobox', { name: 'Status' }).focus();
    await userEvent.keyboard('j');
    (document.activeElement as HTMLElement).blur();
    await userEvent.keyboard('ja');
    expect(rowButton('Item 1')).toHaveAttribute('aria-current', 'true');
    expect(m.calls.filter((c) => c.method === 'PUT')).toHaveLength(0);
  });

  it('Scenario: ? lists the keys in a dialog; Escape closes it and focus goes back', async () => {
    threeItems();
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    rowButton('Item 1').focus();
    await userEvent.keyboard('?');
    const dialog = await screen.findByRole('dialog', { name: 'Keys in the review queue' });
    expect(dialog).toHaveTextContent('Accept the selected item');
    expect(within(dialog).getByRole('button', { name: 'Close' })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(rowButton('Item 1')).toHaveFocus());
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('Scenario: every control names its key', async () => {
    threeItems();
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    expect(screen.getByRole('button', { name: /^Accept/ })).toHaveAttribute(
      'aria-keyshortcuts',
      'a',
    );
    expect(screen.getByRole('button', { name: /^Reject/ })).toHaveAttribute(
      'aria-keyshortcuts',
      'r',
    );
    expect(screen.getByRole('textbox', { name: 'Note (optional)' })).toHaveAttribute(
      'aria-keyshortcuts',
      'n',
    );
    expect(rowButton('Item 1')).toHaveAttribute('aria-keyshortcuts', 'j k');
    expect(screen.getByRole('button', { name: 'Keys (?)' })).toHaveAttribute(
      'aria-keyshortcuts',
      '?',
    );
  });

  it('Scenario: the last item decided leaves the empty state', async () => {
    let items = [item(1)];
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => page(items),
      'GET /admin/review-items/*': () => makeReviewEvidence(item(1)),
      'PUT /admin/review-items/*': () => {
        items = [];
        return { ...item(1), status: 'ACCEPTED' };
      },
    });
    renderApp('/admin/review');
    await screen.findByRole('region', { name: 'Item 1' });
    rowButton('Item 1').focus();
    await userEvent.keyboard('a');
    expect(await screen.findByText('Nothing in this queue.')).toBeInTheDocument();
    expect(screen.getByText('Accepted. 0 left.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Review queue' })).toHaveFocus();
  });
});
