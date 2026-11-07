import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReviewEvidence, ReviewItem } from '@/api/types';
import { ADMIN, makeReviewEvidence } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';

const candidate = (over: Partial<ReviewItem> = {}): ReviewItem => ({
  id: '66666666-6666-5666-8666-666666666661',
  kind: 'RELATIONSHIP_CANDIDATE',
  status: 'OPEN',
  reason: 'About Gemini 4 Carbon',
  details: null,
  resourceId: null,
  resourceTitle: 'Google Gemini Carbon promises a leap',
  createdAt: '2026-11-02T06:00:00Z',
  ...over,
});

const evidence = (
  item: ReviewItem,
  relationship: Partial<NonNullable<ReviewEvidence['relationship']>> = {},
): ReviewEvidence =>
  makeReviewEvidence(item, {
    fields: [],
    validationErrors: [],
    relationship: {
      kind: 'ABOUT',
      target: 'Gemini 4 Carbon',
      targetId: null,
      verification: 'UNVERIFIED',
      origin: 'AI_EXTRACTED',
      quotes: [],
      ...relationship,
    },
  });

const changed = (item: ReviewItem) =>
  evidence(item, {
    target: 'Acme Translator',
    targetId: '11111111-1111-5111-8111-111111111111',
    targetOrigin: 'CURATED',
  });

const conflict = (code: string, detail: string) =>
  new Response(
    JSON.stringify({ type: 'about:blank', title: 'Conflict', status: 409, code, detail }),
    {
      status: 409,
      headers: { 'content-type': 'application/problem+json' },
    },
  );

/** The review list with one item; the evidence comes back changed after the first target change. */
function oneItem(item: ReviewItem, put: () => unknown = () => changed(item)) {
  let after = false;
  return mockApi({
    'GET /me': () => ADMIN,
    'GET /admin/review-items': () => ({ items: [item], total: 1 }),
    'GET /admin/review-items/*': () => (after ? changed(item) : evidence(item)),
    'PUT /admin/review-items/*': () => {
      const out = put();
      if (!(out instanceof Response)) after = true;
      return out;
    },
  });
}

const PUT_PATH = '/admin/review-items/66666666-6666-5666-8666-666666666661/relationship-target';

async function openForm() {
  const user = userEvent.setup();
  renderAdmin('/admin/review');
  const panel = await screen.findByRole('region', { name: 'Evidence' });
  await user.click(await within(panel).findByRole('button', { name: 'Change the target' }));
  return { user, panel };
}

describe('Feature: an admin points a relationship candidate at the right resource (ADR 0064)', () => {
  it('Scenario: the target found in the catalog replaces the name the model gave', async () => {
    const api = oneItem(candidate());
    const { user, panel } = await openForm();
    expect(within(panel).getByRole('searchbox', { name: 'Find in the catalog' })).toHaveValue(
      'Gemini 4 Carbon',
    );
    await user.click(within(panel).getByRole('button', { name: 'Find' }));
    await user.click(
      await within(panel).findByRole('button', { name: 'Point at it Acme Translator' }),
    );

    expect(api.callsTo('PUT', PUT_PATH)[0]?.body).toEqual({
      resourceId: '11111111-1111-5111-8111-111111111111',
    });
    const target = await within(panel).findByText('Acme Translator');
    // A person chose it: not marked as AI, labelled as theirs. The kind stays the model's.
    expect(target.closest('[data-ai-generated]')).toBeNull();
    expect(within(panel).getByText('About')).toHaveAttribute('data-ai-generated', 'true');
    expect(panel).toHaveTextContent('target chosen by an admin');
  });

  it('Scenario: a resource not in the catalog is added with its name, address and type', async () => {
    const api = oneItem(candidate());
    const { user, panel } = await openForm();
    await user.click(within(panel).getByRole('radio', { name: 'Add the resource' }));
    const url = within(panel).getByRole('textbox', { name: 'Official address (https)' });
    await user.clear(url);
    await user.type(url, 'https://deepmind.google/models/gemini/');
    await user.selectOptions(within(panel).getByRole('combobox', { name: 'Type' }), 'Model');
    await user.click(within(panel).getByRole('button', { name: 'Save the target' }));

    await waitFor(() =>
      expect(api.callsTo('PUT', PUT_PATH)[0]?.body).toEqual({
        newResource: {
          title: 'Gemini 4 Carbon',
          url: 'https://deepmind.google/models/gemini/',
          type: 'MODEL',
        },
      }),
    );
  });

  it('Scenario: a refused value says why, and the form stays open', async () => {
    oneItem(candidate(), () =>
      problem(400, 'Invalid request', "The address must be the resource's own https address."),
    );
    const { user, panel } = await openForm();
    await user.click(within(panel).getByRole('radio', { name: 'Another name only' }));
    await user.click(within(panel).getByRole('button', { name: 'Save the target' }));

    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      "The address must be the resource's own https address.",
    );
    expect(within(panel).getByRole('button', { name: 'Save the target' })).toBeEnabled();
  });

  it('Scenario: an item someone decided meanwhile says so', async () => {
    oneItem(candidate(), () => conflict('already-decided', 'The item is no longer open.'));
    const { user, panel } = await openForm();
    await user.click(within(panel).getByRole('radio', { name: 'Another name only' }));
    await user.click(within(panel).getByRole('button', { name: 'Save the target' }));

    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      'Someone decided this item meanwhile. Reload the list.',
    );
  });

  it('Scenario: a decided candidate has no target to change', async () => {
    oneItem(candidate({ status: 'ACCEPTED', resolvedAt: '2026-11-02T07:00:00Z' }));
    renderAdmin('/admin/review?status=ACCEPTED');
    const panel = await screen.findByRole('region', { name: 'Evidence' });
    await within(panel).findByText('Gemini 4 Carbon');
    expect(within(panel).queryByRole('button', { name: 'Change the target' })).toBeNull();
  });
});
