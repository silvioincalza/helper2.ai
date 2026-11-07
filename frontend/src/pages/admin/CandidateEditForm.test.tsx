import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReviewItem } from '@/api/types';
import { ADMIN, makeReviewEvidence } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';
import { tagsOf } from './CandidateEditForm';

const details = (over: Record<string, unknown> = {}) =>
  JSON.stringify({
    version: 2,
    sourceId: 'discovery-ai-assistants',
    url: 'https://engadget.com/2300677/google-gemini-carbon',
    title: 'Google Gemini Carbon promises a leap | Engadget',
    type: 'PRODUCT',
    tags: ['ai-assistants'],
    foundBy: [{ sourceId: 'discovery-ai-assistants' }],
    ...over,
  });

const candidate = (over: Partial<ReviewItem> = {}): ReviewItem => ({
  id: '77777777-7777-5777-8777-777777777771',
  kind: 'RESOURCE_CANDIDATE',
  status: 'OPEN',
  reason: 'New PRODUCT (ai-assistants) found by discovery-ai-assistants: Google Gemini Carbon',
  details: details(),
  resourceId: null,
  resourceTitle: null,
  categories: ['ai-assistants'],
  createdAt: '2026-11-02T06:00:00Z',
  ...over,
});

const PUT_PATH = '/admin/review-items/77777777-7777-5777-8777-777777777771/candidate';

function oneItem(put: () => unknown = () => candidate()) {
  let edited = false;
  return mockApi({
    'GET /me': () => ADMIN,
    'GET /admin/review-items': () => ({
      items: [
        edited
          ? candidate({ details: details({ type: 'NEWS', edited: ['title', 'type'] }) })
          : candidate(),
      ],
      total: 1,
    }),
    'GET /admin/review-items/*': () =>
      makeReviewEvidence(candidate(), { run: null, fields: [], validationErrors: [] }),
    'PUT /admin/review-items/*': () => {
      const out = put();
      if (!(out instanceof Response)) edited = true;
      return out;
    },
  });
}

async function openForm() {
  const user = userEvent.setup();
  renderAdmin('/admin/review');
  const detail = await screen.findByRole('region', { name: candidate().reason });
  await user.click(await within(detail).findByRole('button', { name: 'Edit the candidate' }));
  return { user, detail };
}

describe('Feature: an admin fixes a resource candidate before accepting it (ADR 0026)', () => {
  it('Scenario: only what changed is sent, and the list shows the new values', async () => {
    const api = oneItem();
    const { user, detail } = await openForm();
    const title = within(detail).getByRole('textbox', { name: 'Title' });
    await user.clear(title);
    await user.type(title, 'Google Gemini Carbon promises a leap');
    await user.selectOptions(within(detail).getByRole('combobox', { name: 'Type' }), 'News');
    await user.click(within(detail).getByRole('button', { name: 'Save the changes' }));

    await waitFor(() =>
      expect(api.callsTo('PUT', PUT_PATH)[0]?.body).toEqual({
        // The details the admin saw: the server refuses the edit when they changed since.
        expectedDetails: details(),
        title: 'Google Gemini Carbon promises a leap',
        type: 'NEWS',
      }),
    );
    // The list reloads (the detail is drawn again): it shows what the admin wrote.
    expect(await screen.findByText('Changed by an admin: title, type.')).toBeInTheDocument();
    const reloaded = screen.getByRole('region', { name: candidate().reason });
    expect(within(reloaded).getByText('News')).toBeInTheDocument();
  });

  it('Scenario: nothing changed sends nothing', async () => {
    const api = oneItem();
    const { user, detail } = await openForm();
    await user.click(within(detail).getByRole('button', { name: 'Save the changes' }));
    expect(api.callsTo('PUT', PUT_PATH)).toHaveLength(0);
    expect(within(detail).getByRole('button', { name: 'Edit the candidate' })).toBeInTheDocument();
  });

  it('Scenario: a refused value says why, and the form stays open', async () => {
    oneItem(() => problem(400, 'Invalid request', 'At most 12 tags, each in lower case words.'));
    const { user, detail } = await openForm();
    await user.type(
      within(detail).getByRole('textbox', { name: 'Tags, separated by commas' }),
      ', x',
    );
    await user.click(within(detail).getByRole('button', { name: 'Save the changes' }));
    expect(await within(detail).findByRole('alert')).toHaveTextContent(
      'At most 12 tags, each in lower case words.',
    );
  });

  it('Scenario: a decided candidate has no edit button', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => ({
        items: [candidate({ status: 'ACCEPTED', resolvedAt: '2026-11-02T07:00:00Z' })],
        total: 1,
      }),
      'GET /admin/review-items/*': () =>
        makeReviewEvidence(candidate(), { run: null, fields: [], validationErrors: [] }),
    });
    renderAdmin('/admin/review?status=ACCEPTED');
    const detail = await screen.findByRole('region', { name: candidate().reason });
    await within(detail).findByText('Google Gemini Carbon promises a leap | Engadget');
    expect(within(detail).queryByRole('button', { name: 'Edit the candidate' })).toBeNull();
  });

  it('Scenario: tags are read from the text as the server wants them', () => {
    expect(tagsOf(' Local-AI, self-hosted,, local-ai ')).toEqual(['local-ai', 'self-hosted']);
  });
});
