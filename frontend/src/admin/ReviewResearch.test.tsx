import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { ADMIN } from '@/test/fixtures';
import { mockApi, type Handler } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';
import { fieldOf, outcomeOf } from './ResearchDecisions';

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

  it('Scenario: an edited value the candidate cannot take is refused, and Accept keeps waiting', async () => {
    researchApi();
    renderAdmin('/admin/review?kind=RESOURCE_CANDIDATE');
    const detail = await screen.findByRole('region', { name: /AcmeTeam/ });
    await within(detail).findByText('Proposed with AI.');

    await userEvent.click(within(detail).getByRole('button', { name: 'Edit Pricing' }));
    const value = within(detail).getByRole('textbox', { name: 'New value for Pricing' });
    await userEvent.clear(value);
    await userEvent.type(value, 'cheap');
    await userEvent.click(within(detail).getByRole('button', { name: 'Save' }));

    const why = within(detail).getByText(
      'Use one of: FREE, FREEMIUM, PAID, OPEN_SOURCE_SELF_HOSTED.',
    );
    expect(why).toHaveAttribute('role', 'alert');
    expect(value).toHaveAttribute('aria-invalid', 'true');
    expect(value).toHaveAccessibleDescription(
      'Use one of: FREE, FREEMIUM, PAID, OPEN_SOURCE_SELF_HOSTED.',
    );
    expect(within(detail).getByText('Decide 3 more changes to accept.')).toBeInTheDocument();
    expect(await axeViolations(detail)).toEqual([]);

    await userEvent.clear(value);
    await userEvent.type(value, 'PAID');
    expect(value).not.toHaveAttribute('aria-invalid');
    await userEvent.click(within(detail).getByRole('button', { name: 'Save' }));
    expect(within(detail).getByText('Decide 2 more changes to accept.')).toBeInTheDocument();
    await settle();
  });

  it('Scenario: the keys c and u decide the next change, and queue keys stay in the research', async () => {
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
      await screen.findByRole('button', { name: 'Reject 1 the AI marked as reject…' }),
    );
    const dialog = await screen.findByRole('dialog', {
      name: 'Reject 1 item the AI marked as reject?',
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

describe('admin: research journeys in the review queue (ADR 0048)', () => {
  const QUEUE = '/admin/review?kind=RESOURCE_CANDIDATE';

  it('Scenario: decisions stay with their run across j, k and the steps page', async () => {
    researchApi();
    const { container } = renderAdmin(QUEUE);
    const detail = await screen.findByRole('region', { name: /AcmeTeam/ });
    await userEvent.click(await within(detail).findByRole('button', { name: 'Confirm Title' }));
    expect(within(detail).getByText('1 of 3 changes decided')).toBeInTheDocument();
    await settle();
    expect(await axeViolations(container)).toEqual([]);

    act(() => screen.getByRole('checkbox', { name: /AcmeTeam/ }).focus());
    await userEvent.keyboard('j');
    expect(await screen.findByRole('region', { name: /Free AI Note Taker/ })).toBeInTheDocument();
    await userEvent.keyboard('k');
    const back = await screen.findByRole('region', { name: /AcmeTeam/ });
    expect(await within(back).findByText('1 of 3 changes decided')).toBeInTheDocument();

    await userEvent.click(within(back).getByRole('link', { name: 'Show every step' }));
    await userEvent.click(await screen.findByRole('link', { name: 'Back to the review queue' }));
    const again = await screen.findByRole('region', { name: /AcmeTeam/ });
    expect(await within(again).findByText('1 of 3 changes decided')).toBeInTheDocument();
    await settle();
  });

  it('Scenario: focus moves to the next change, back to an undone one, and to Accept at the end', async () => {
    const m = researchApi();
    renderAdmin(QUEUE);
    const detail = await screen.findByRole('region', { name: /AcmeTeam/ });
    await userEvent.click(await within(detail).findByRole('button', { name: 'Confirm Title' }));
    expect(within(detail).getByRole('button', { name: 'Confirm Pricing' })).toHaveFocus();
    await userEvent.keyboard('a');
    expect(m.callsTo('PUT', `/admin/review-items/${ACME}/resolution`)).toHaveLength(0);

    await userEvent.click(within(detail).getByRole('button', { name: 'Undo Title' }));
    expect(within(detail).getByRole('button', { name: 'Confirm Title' })).toHaveFocus();

    await userEvent.click(within(detail).getByRole('button', { name: 'Keep the rest as now (3)' }));
    expect(within(detail).getByText('3 of 3 changes decided')).toBeInTheDocument();
    expect(
      within(detail).getByRole('button', { name: /Accept with the confirmed values/ }),
    ).toHaveFocus();
    await settle();
  });

  it('Scenario: the detail pane keeps a focused item clear of the decide bar, as tall as it is', async () => {
    const real = HTMLElement.prototype.getBoundingClientRect;
    const spy = vi
      .spyOn(HTMLElement.prototype, 'getBoundingClientRect')
      .mockImplementation(function (this: HTMLElement) {
        return this.classList.contains('h2-admin__decide')
          ? ({ height: 172 } as DOMRect)
          : real.call(this);
      });
    try {
      researchApi();
      renderAdmin(QUEUE);
      const detail = await screen.findByRole('region', { name: /AcmeTeam/ });
      await waitFor(() => expect(detail.style.getPropertyValue('--decide-h')).toBe('172px'));
      await settle();
    } finally {
      spy.mockRestore();
    }
  });

  it('Scenario: a while changes wait says why nothing happened', async () => {
    const m = researchApi();
    renderAdmin(QUEUE);
    const detail = await screen.findByRole('region', { name: /AcmeTeam/ });
    const accept = await within(detail).findByRole('button', {
      name: /Accept with the confirmed values/,
    });
    act(() => accept.focus());
    await userEvent.keyboard('a');
    expect(screen.getAllByText('Decide 3 more changes to accept.')).toHaveLength(2);
    expect(m.callsTo('PUT', `/admin/review-items/${ACME}/resolution`)).toHaveLength(0);
    await settle();
  });

  it('Scenario: research for this page leaves out the items already researched', async () => {
    const m = researchApi({ 'POST /admin/review-research': () => ({ runs: [] }) });
    renderAdmin(QUEUE);
    const page = await screen.findByRole('button', { name: 'Research this page (1)' });
    await waitFor(() => expect(page).toHaveAccessibleDescription('1 new, 2 already researched'));
    expect(screen.getAllByRole('button', { name: /^Research again/ })).toHaveLength(2);
    await userEvent.click(page);
    expect(m.callsTo('POST', '/admin/review-research')[0]?.body).toEqual({
      itemIds: [FRESH],
      allOpen: false,
    });
    await settle();
  });

  it('Scenario: a queued run can be watched live, and a cancelled one still shows its steps', async () => {
    researchApi({
      'GET /admin/review-research': () => ({
        runs: [
          { ...run(RUN_ACME, ACME, 'KEEP'), status: 'QUEUED', verdict: null },
          { ...run(RUN_LIST, LIST, 'REJECT'), status: 'CANCELLED', verdict: null },
        ],
      }),
    });
    renderAdmin(QUEUE);
    expect(await screen.findAllByRole('link', { name: 'Watch live' })).not.toHaveLength(0);
    for (const live of screen.getAllByRole('link', { name: 'Watch live' }))
      expect(live).toHaveAttribute('href', `/admin/review/research/${RUN_ACME}`);
    expect(screen.getByRole('link', { name: '10 steps' })).toHaveAttribute(
      'href',
      `/admin/review/research/${RUN_LIST}`,
    );
    expect(
      screen.getByRole('button', { name: /^Research New ARTICLE found by discovery/ }),
    ).toBeInTheDocument();
    await settle();
  });

  it('Scenario: a research filter that matches nothing can be cleared, and a new filter goes back to the first page', async () => {
    researchApi({
      'GET /admin/review-items': ({ url }) =>
        url.searchParams.get('research') ? { items: [], total: 0 } : ITEMS,
    });
    const { params } = renderAdmin(`${QUEUE}&research=DUPLICATE&page=2`);
    expect(await screen.findByText('No item matches this research filter.')).toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Research'), 'Queued or running');
    expect(params().get('research')).toBe('ACTIVE');
    expect(params().get('page')).toBeNull();
    await userEvent.click(await screen.findByRole('button', { name: 'Clear the research filter' }));
    expect(params().get('research')).toBeNull();
    await settle();
  });

  it('Scenario: the reasons in the batch reject are marked as written with AI', async () => {
    researchApi();
    renderAdmin(QUEUE);
    await userEvent.click(
      await screen.findByRole('button', { name: 'Reject 1 the AI marked as reject…' }),
    );
    const dialog = await screen.findByRole('dialog', {
      name: 'Reject 1 item the AI marked as reject?',
    });
    expect(within(dialog).getByText('Reasons written with AI.')).toBeInTheDocument();
    const reason = await within(dialog).findByText('A list of other note takers, not one product.');
    expect(reason.closest('[data-ai-generated="true"]')).not.toBeNull();
    expect(reason.closest('li')).toHaveAttribute('data-ai-model', 'nvidia/Nemotron-3.5-Super');
    await settle();
    expect(await axeViolations(document.body)).toEqual([]);
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
    });
  });

  it('a decided value the candidate cannot take is still left to decide, and is not saved', () => {
    const out = outcomeOf(
      [
        attr('type', 'PRODUCT', ['LIBRARY']),
        attr('openSource', null, ['true']),
        attr('tags', 'x', ['meetings']),
        attr('title', 'Old', ['New']),
      ],
      {
        type: { kind: 'edit', value: 'library' },
        openSource: { kind: 'edit', value: 'yes' },
        tags: { kind: 'edit', value: 'Note Taking!' },
        title: { kind: 'confirm', value: 'New' },
      },
    );
    expect(out).toEqual({
      complete: false,
      left: ['type', 'openSource', 'tags'],
      edit: { title: 'New' },
    });
  });

  it('each field takes only what the candidate edit takes', () => {
    expect(fieldOf('type', 'LIBRARY')).toEqual({ type: 'LIBRARY' });
    expect(fieldOf('type', 'library')).toBeNull();
    expect(fieldOf('openSource', 'false')).toEqual({ openSource: false });
    expect(fieldOf('openSource', 'yes')).toBeNull();
    expect(fieldOf('tags', 'note taking, rag')).toEqual({ tags: ['note-taking', 'rag'] });
    expect(fieldOf('tags', 'Note Taking!')).toBeNull();
    expect(fieldOf('canonicalUrl', 'https://acme.example/')).toEqual({
      canonicalUrl: 'https://acme.example/',
    });
    expect(fieldOf('canonicalUrl', 'http://acme.example/')).toBeNull();
    expect(fieldOf('pricing', 'FREE')).toEqual({ pricing: 'FREE' });
    expect(fieldOf('pricing', 'free')).toBeNull();
    expect(fieldOf('license', 'Apache-2.0')).toEqual({ license: 'Apache-2.0' });
    expect(fieldOf('license', 'MIT licence')).toBeNull();
    expect(fieldOf('audience', 'BOTH')).toEqual({ audience: 'BOTH' });
    expect(fieldOf('techLevel', 'EXPERT')).toBeNull();
    expect(fieldOf('language', 'de')).toEqual({ language: 'de' });
    expect(fieldOf('language', 'deu')).toBeNull();
    expect(fieldOf('publishedAt', '2025-03-02')).toEqual({ publishedAt: '2025-03-02' });
    expect(fieldOf('publishedAt', '2025-02-30')).toBeNull();
    expect(fieldOf('title', 'x'.repeat(301))).toBeNull();
  });

  it('a description the admin rewrote is not marked as written with AI', () => {
    const out = outcomeOf([attr('description', null, ['Writes meeting notes.'])], {
      description: { kind: 'edit', value: 'Takes notes in calls.' },
    });
    expect(out.edit).toEqual({
      description: 'Takes notes in calls.',
      descriptionWrittenByAi: false,
    });
  });

  it('a course candidate saves its confirmed values like a resource candidate', () => {
    const out = outcomeOf(
      [
        attr('title', 'Old', ['New']),
        attr('pricing', null, ['FREE']),
        attr('type', 'COURSE', ['COURSE']),
      ],
      { title: { kind: 'edit', value: 'Newer' }, pricing: { kind: 'confirm', value: 'FREE' } },
    );
    expect(out.complete).toBe(true);
    expect(out.edit).toEqual({ title: 'Newer', pricing: 'FREE' });
  });
});
