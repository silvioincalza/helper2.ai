import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReviewItem } from '@/api/types';
import { ADMIN, makeReviewEvidence } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';
import { FieldCuration } from './FieldCuration';

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

const DIRECT = '77777777-7777-5777-8777-777777777777';
const path = (field: string) => `/admin/resources/${DIRECT}/fields/${field}`;

describe('Feature: an admin sets a resource value the sources do not decide (ADR 0038)', () => {
  it('Scenario: a description is written in a text box and sent as the admin wrote it', async () => {
    const api = mockApi({ 'PUT /admin/resources/*': () => undefined });
    const changed = vi.fn();
    render(
      <FieldCuration
        resourceId={DIRECT}
        field="description"
        value="Old text."
        origin="AI_SUMMARY"
        onChanged={changed}
      />,
    );
    expect(screen.queryByRole('button', { name: /Drop the AI value/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Set the value/ }));
    const box = screen.getByRole('textbox');
    expect(box.tagName).toBe('TEXTAREA');
    await userEvent.clear(box);
    await userEvent.type(box, 'Writes meeting notes for small teams.');
    await userEvent.click(screen.getByRole('button', { name: 'Save the value' }));
    await waitFor(() => expect(changed).toHaveBeenCalled());
    expect(api.callsTo('PUT', path('description'))[0]?.body).toEqual({
      value: 'Writes meeting notes for small teams.',
    });
  });

  it('Scenario: a language is picked from the catalog languages and a date from a date field', async () => {
    const api = mockApi({ 'PUT /admin/resources/*': () => undefined });
    const { unmount } = render(
      <FieldCuration
        resourceId={DIRECT}
        field="language"
        value="en"
        origin="SOURCE_PROVIDED"
        onChanged={() => {}}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: /Set the value/ }));
    await userEvent.selectOptions(screen.getByRole('combobox'), 'de');
    await userEvent.click(screen.getByRole('button', { name: 'Save the value' }));
    await waitFor(() =>
      expect(api.callsTo('PUT', path('language'))[0]?.body).toEqual({ value: 'de' }),
    );
    unmount();

    render(
      <FieldCuration
        resourceId={DIRECT}
        field="publishedAt"
        value="2025-03-02"
        origin="SOURCE_PROVIDED"
        onChanged={() => {}}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: /Set the value/ }));
    expect(screen.getByDisplayValue('2025-03-02')).toHaveAttribute('type', 'date');
  });
});
