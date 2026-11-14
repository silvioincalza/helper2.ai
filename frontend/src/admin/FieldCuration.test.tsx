import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReviewItem } from '@/api/types';
import { ADMIN, makeReviewEvidence } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';

const RESOURCE = '88888888-8888-5888-8888-888888888881';
const check: ReviewItem = {
  id: '88888888-8888-5888-8888-888888888882',
  kind: 'ENRICHMENT_VALIDATION',
  status: 'OPEN',
  reason: 'Enrichment check of Engadget news',
  details: null,
  resourceId: RESOURCE,
  resourceTitle: 'Google Gemini Carbon promises a leap',
  createdAt: '2026-11-02T06:18:00Z',
};

function oneCheck(put: () => unknown = () => undefined) {
  return mockApi({
    'GET /me': () => ADMIN,
    'GET /admin/review-items': () => ({ items: [check], total: 1 }),
    'GET /admin/review-items/*': () =>
      makeReviewEvidence(check, {
        validationErrors: [],
        fields: [
          {
            field: 'pricing',
            value: 'FREEMIUM',
            origin: 'AI_EXTRACTED',
            verification: 'VERIFIED',
            quotes: [],
          },
          {
            field: 'useCase',
            value: 'coding',
            origin: 'AI_EXTRACTED',
            verification: 'VERIFIED',
            quotes: [],
          },
        ],
      }),
    'PUT /admin/resources/*': put,
    'POST /admin/resources/*': () => undefined,
  });
}

async function evidence() {
  const user = userEvent.setup();
  renderAdmin('/admin/review');
  const panel = await screen.findByRole('region', { name: 'Evidence' });
  await within(panel).findByText('FREEMIUM');
  return { user, panel };
}

describe('Feature: an admin fixes a field of a resource from its enrichment check (ADR 0038)', () => {
  it('Scenario: the admin sets the price, which wins over every source and run', async () => {
    const api = oneCheck();
    const { user, panel } = await evidence();
    await user.click(within(panel).getByRole('button', { name: 'Set the value Pricing' }));
    await user.selectOptions(within(panel).getByRole('combobox', { name: 'Pricing' }), 'Paid');
    await user.click(within(panel).getByRole('button', { name: 'Save the value' }));

    await waitFor(() =>
      expect(api.callsTo('PUT', `/admin/resources/${RESOURCE}/fields/pricing`)[0]?.body).toEqual({
        value: 'PAID',
      }),
    );
    await waitFor(() =>
      expect(api.calls.filter((c) => c.path.endsWith('/evidence')).length).toBeGreaterThan(1),
    );
  });

  it('Scenario: the admin drops what the AI said about the use case', async () => {
    const api = oneCheck();
    const { user, panel } = await evidence();
    expect(within(panel).queryByRole('button', { name: 'Set the value Use cases' })).toBeNull();
    await user.click(within(panel).getByRole('button', { name: 'Drop the AI value Use cases' }));

    await waitFor(() =>
      expect(api.callsTo('POST', `/admin/resources/${RESOURCE}/fields/useCase/drop`)).toHaveLength(
        1,
      ),
    );
  });

  it('Scenario: a value the field cannot take says why', async () => {
    oneCheck(() => problem(400, 'Invalid request', 'This value is not allowed for pricing.'));
    const { user, panel } = await evidence();
    await user.click(within(panel).getByRole('button', { name: 'Set the value Pricing' }));
    await user.click(within(panel).getByRole('button', { name: 'Save the value' }));

    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      'This value is not allowed for pricing.',
    );
  });
});
