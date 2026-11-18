import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN } from '@/test/fixtures';
import { mockApi, type Handler } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';
import { outcomeOf } from './ResearchDecisions';

const settle = () =>
  act(async () => {
    await new Promise((r) => setTimeout(r, 50));
  });

const ACME = '11111111-1111-4111-8111-111111111111';
const LIST = '22222222-2222-4222-8222-222222222222';
const FRESH = '33333333-3333-4333-8333-333333333333';
const RUN_ACME = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const RUN_LIST = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';

const acmeDetails = JSON.stringify({
  sourceId: 'discovery-meetings-and-transcription',
  url: 'https://acmenotes.example/ai-meeting-notetaker',
  title: 'AI Meeting Notetaker | Transcripts | AcmeTeam',
  type: 'PRODUCT',
  tags: ['meetings-and-transcription'],
});

function item(id: string, reason: string, details: string | null = null) {
  return {
    id,
    kind: 'RESOURCE_CANDIDATE',
    status: 'OPEN',
    reason,
    details,
    resourceId: null,
    resourceTitle: null,
    createdAt: '2026-11-17T05:48:00Z',
  };
}

const ITEMS = {
  items: [
    item(ACME, 'New PRODUCT found by discovery: AI Meeting Notetaker | AcmeTeam', acmeDetails),
    item(LIST, 'New ARTICLE found by discovery: Free AI Note Taker for Windows (2026)'),
    item(FRESH, 'New AGENT found by github-sdks: langchain-ai/design-agent'),
  ],
  total: 3,
};

function run(id: string, itemId: string, verdict: 'KEEP' | 'REJECT') {
  return {
    id,
    itemId,
    status: 'DONE',
    model: 'nvidia/Nemotron-3.5-Super',
    requestedAt: '2026-11-18T02:00:00Z',
    startedAt: '2026-11-18T02:00:01Z',
    finishedAt: '2026-11-18T02:00:42Z',
    steps: 10,
    promptTokens: 15912,
    completionTokens: 2328,
    verdict,
    error: null,
  };
}

const source = (evidenceId: string, value: string, url = 'https://acmenotes.example') => ({
  evidenceId,
  tool: 'read_page',
  url,
  excerpt: 'Acme Notes joins your video calls and writes the transcript.',
  value,
  authoritative: true,
});

const ACME_DETAIL = {
  run: run(RUN_ACME, ACME, 'KEEP'),
  result: {
    verdict: 'KEEP',
    reason: 'One meeting notetaker product, with its own site.',
    duplicateOf: null,
    aiWritten: true,
    attributes: [
      {
        name: 'title',
        current: 'AI Meeting Notetaker | Transcripts | AcmeTeam',
        proposed: ['Acme Notes'],
        status: 'CONFIRMED',
        sources: [source('e1', 'Acme Notes')],
      },
      {
        name: 'type',
        current: 'PRODUCT',
        proposed: ['PRODUCT'],
        status: 'CONFIRMED',
        sources: [source('e1', 'PRODUCT')],
      },
      {
        name: 'pricing',
        current: null,
        proposed: ['FREEMIUM'],
        status: 'CONFIRMED',
        sources: [source('e2', 'FREEMIUM', 'https://acmenotes.example/pricing')],
      },
      {
        name: 'publishedAt',
        current: null,
        proposed: ['2025-03-02', '2024-11-18'],
        status: 'CONFLICT',
        sources: [
          source('e1', '2025-03-02'),
          source('e3', '2024-11-18', 'https://reviews.example'),
        ],
      },
      { name: 'license', current: null, proposed: [], status: 'NOT_FOUND', sources: [] },
    ],
    dropped: ['license (e3): the excerpt does not state MIT'],
  },
  steps: [],
};

function researchApi(over: Record<string, Handler> = {}) {
  return mockApi({
    'GET /me': () => ADMIN,
    'GET /admin/review-items': () => ITEMS,
    'GET /admin/overview': () => ({ openReviewItems: [] }),
    'GET /admin/review-research': () => ({
      runs: [run(RUN_ACME, ACME, 'KEEP'), run(RUN_LIST, LIST, 'REJECT')],
    }),
    [`GET /admin/review-research/${RUN_ACME}`]: () => ACME_DETAIL,
    [`GET /admin/review-research/${RUN_LIST}`]: () => ({
      run: run(RUN_LIST, LIST, 'REJECT'),
      result: {
        verdict: 'REJECT',
        reason: 'A list of other note takers, not one product.',
        duplicateOf: null,
        aiWritten: true,
        attributes: [],
        dropped: [],
      },
      steps: [],
    }),
    ...over,
  });
}

describe('admin: research in the review queue (ADR 0048)', () => {
  it('Scenario: each row shows its research, and research starts for the picked items', async () => {
    const m = researchApi({
      'POST /admin/review-research': () => ({
        runs: [{ ...run(RUN_ACME, FRESH, 'KEEP'), status: 'QUEUED' }],
      }),
    });
    renderAdmin('/admin/review?kind=RESOURCE_CANDIDATE');
    const box = await screen.findByRole('checkbox', { name: /langchain-ai\/design-agent/ });
    expect(await screen.findAllByRole('link', { name: '10 steps' })).toHaveLength(2);
    await userEvent.click(box);
    await userEvent.click(screen.getByRole('button', { name: 'Research 1 selected' }));
    expect(m.callsTo('POST', '/admin/review-research')[0]?.body).toEqual({
      itemIds: [FRESH],
      allOpen: false,
    });
    expect(await screen.findByText('1 item queued for research.')).toBeInTheDocument();
    await settle();
  });

  it('Scenario: the proposal is marked as written with AI, and Accept waits until every change is decided', async () => {
    const m = researchApi({
      [`PUT /admin/review-items/${ACME}/candidate`]: () => ITEMS.items[0],
      [`PUT /admin/review-items/${ACME}/resolution`]: () => ({
        ...ITEMS.items[0],
        status: 'ACCEPTED',
      }),
    });
    renderAdmin('/admin/review?kind=RESOURCE_CANDIDATE');
    const detail = await screen.findByRole('region', { name: /AcmeTeam/ });
    const research = await within(detail).findByText('Proposed with AI.');
    expect(research.closest('[data-ai-generated="true"]')).not.toBeNull();
    const accept = await within(detail).findByRole('button', {
      name: /Accept with the confirmed values/,
    });
    expect(accept).toHaveAttribute('aria-disabled', 'true');
    expect(within(detail).getByText('Decide 3 more changes to accept.')).toBeInTheDocument();
    expect(
      within(detail).getByText('license (e3): the excerpt does not state MIT'),
    ).toBeInTheDocument();

    await userEvent.click(within(detail).getByRole('button', { name: 'Confirm Title' }));
    await userEvent.click(within(detail).getByRole('button', { name: 'Confirm Pricing' }));
    const pick = within(detail).getByRole('button', { name: 'Confirm pick Published' });
    expect(pick).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(within(detail).getByRole('radio', { name: '2025-03-02' }));
    await userEvent.click(pick);
    expect(within(detail).getByText('3 of 3 changes decided')).toBeInTheDocument();
    expect(accept).not.toHaveAttribute('aria-disabled');

    await userEvent.click(accept);
    await waitFor(() =>
      expect(m.callsTo('PUT', `/admin/review-items/${ACME}/resolution`)).toHaveLength(1),
    );
    expect(m.callsTo('PUT', `/admin/review-items/${ACME}/candidate`)[0]?.body).toEqual({
      expectedDetails: acmeDetails,
      title: 'Acme Notes',
      pricing: 'FREEMIUM',
      publishedAt: '2025-03-02',
    });
    expect(m.callsTo('PUT', `/admin/review-items/${ACME}/resolution`)[0]?.body).toEqual({
      status: 'ACCEPTED',
    });
    await settle();
  });

  it('Scenario: the keys c and u decide the next change, and k still moves up the list', async () => {
    researchApi();
    renderAdmin('/admin/review?kind=RESOURCE_CANDIDATE');
    const detail = await screen.findByRole('region', { name: /AcmeTeam/ });
    (await within(detail).findByRole('button', { name: 'Confirm Title' })).focus();
    await userEvent.keyboard('c');
    await userEvent.keyboard('u');
    expect(within(detail).getByText('2 of 3 changes decided')).toBeInTheDocument();
    expect(within(detail).getByText('Kept')).toBeInTheDocument();
    await settle();
  });

  it('Scenario: rejecting the items researched as reject shows the list with reasons first', async () => {
    const m = researchApi({
      'POST /admin/review-items/resolutions': () => ({
        items: [{ id: LIST, outcome: 'RESOLVED' }],
        batch: 'b1',
      }),
    });
    renderAdmin('/admin/review?kind=RESOURCE_CANDIDATE');
    await userEvent.click(
      await screen.findByRole('button', { name: 'Reject the 1 researched as reject…' }),
    );
    const dialog = await screen.findByRole('dialog', {
      name: 'Reject 1 item researched as reject?',
    });
    expect(
      await within(dialog).findByText('A list of other note takers, not one product.'),
    ).toBeInTheDocument();
    expect(m.callsTo('POST', '/admin/review-items/resolutions')).toHaveLength(0);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reject 1 item' }));
    expect(m.callsTo('POST', '/admin/review-items/resolutions')[0]?.body).toEqual({
      ids: [LIST],
      status: 'REJECTED',
      note: 'Rejected after research',
    });
    await settle();
  });

  it('Scenario: research on every open candidate shows the count before it queues', async () => {
    const m = researchApi({
      'GET /admin/review-research/estimate': () => ({ items: 9398, maxModelCalls: 131572 }),
      'POST /admin/review-research': () => ({ runs: [] }),
    });
    renderAdmin('/admin/review?kind=RESOURCE_CANDIDATE');
    await userEvent.click(await screen.findByRole('button', { name: 'Research all open…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Research every open candidate?' });
    expect(await within(dialog).findByText('9,398')).toBeInTheDocument();
    expect(within(dialog).getByText('131,572')).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Queue 9,398 items' }));
    expect(m.callsTo('POST', '/admin/review-research')[0]?.body).toEqual({ allOpen: true });
    await settle();
  });

  it('Scenario: the research filter asks the server for the items not researched yet', async () => {
    const m = researchApi({
      'GET /admin/review-items': ({ url }) =>
        url.searchParams.get('research') === 'NONE'
          ? { items: ITEMS.items.filter((i) => i.id === FRESH), total: 1 }
          : ITEMS,
    });
    renderAdmin('/admin/review?kind=RESOURCE_CANDIDATE&research=NONE');
    expect(await screen.findByRole('region', { name: /design-agent/ })).toBeInTheDocument();
    expect(m.callsTo('GET', '/admin/review-items')[0]?.search).toContain('research=NONE');
    await settle();
    expect(screen.queryByRole('checkbox', { name: /AcmeTeam/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /Free AI Note Taker/ })).not.toBeInTheDocument();
  });
});

describe('admin: research outcome (ADR 0048)', () => {
  const attr = (name: string, current: string | null, proposed: string[]) => ({
    name,
    current,
    proposed,
    status: 'CONFIRMED' as const,
    sources: [],
  });

  it('saves every confirmed value through the candidate, and a kept one not at all', () => {
    const out = outcomeOf(
      [
        attr('title', 'Old', ['New']),
        attr('type', 'PRODUCT', ['LIBRARY']),
        attr('tags', 'x', ['meetings, note taking']),
        attr('license', null, ['MIT']),
        attr('openSource', null, ['true']),
        attr('canonicalUrl', 'https://a.example/x', ['https://a.example/']),
        attr('description', null, ['Writes meeting notes.']),
        attr('language', null, ['en']),
      ],
      {
        title: { kind: 'confirm', value: 'New' },
        type: { kind: 'confirm', value: 'LIBRARY' },
        tags: { kind: 'confirm', value: 'meetings, note taking' },
        license: { kind: 'keep' },
        openSource: { kind: 'confirm', value: 'true' },
        canonicalUrl: { kind: 'confirm', value: 'https://a.example/' },
        description: { kind: 'confirm', value: 'Writes meeting notes.' },
        language: { kind: 'confirm', value: 'en' },
      },
      true,
    );
    expect(out).toEqual({
      complete: true,
      left: [],
      edit: {
        title: 'New',
        type: 'LIBRARY',
        tags: ['meetings', 'note-taking'],
        openSource: true,
        canonicalUrl: 'https://a.example/',
        description: 'Writes meeting notes.',
        descriptionWrittenByAi: true,
        language: 'en',
      },
      note: '',
    });
  });

  it('a description the admin rewrote is not marked as written with AI', () => {
    const out = outcomeOf(
      [attr('description', null, ['Writes meeting notes.'])],
      { description: { kind: 'edit', value: 'Takes notes in calls.' } },
      true,
    );
    expect(out.edit).toEqual({
      description: 'Takes notes in calls.',
      descriptionWrittenByAi: false,
    });
  });

  it('a course candidate holds no values: every confirmed value goes to the decision note', () => {
    const out = outcomeOf(
      [attr('title', 'Old', ['New']), attr('pricing', null, ['FREE'])],
      { title: { kind: 'edit', value: 'Newer' } },
      false,
    );
    expect(out.complete).toBe(false);
    expect(out.left).toEqual(['pricing']);
    expect(out.edit).toBeNull();
    expect(out.note).toBe('Research confirmed: title=Newer');
  });
});
