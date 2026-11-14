import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AdminHomeExample } from '@/api/types';
import { ADMIN, makeCard, makeSearch } from '@/test/fixtures';
import { json, mockApi } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';

const DEEPL = '11111111-1111-5111-8111-111111111111';

function example(over: Partial<AdminHomeExample> = {}): AdminHomeExample {
  return {
    id: '22222222-2222-5222-8222-222222222222',
    label: 'Translate documents',
    query: 'translate documents',
    path: 'USE_AI',
    status: 'ACTIVE',
    origin: 'CURATED',
    model: null,
    expected: [{ id: DEEPL, title: 'DeepL Translator' }],
    check: { outcome: 'PASS', bestRank: 2, resultCount: 40, checkedAt: '2026-11-05T02:41:00Z' },
    version: 3,
    updatedAt: '2026-11-04T10:00:00Z',
    ...over,
  };
}

const list = () => screen.findByRole('list', { name: 'Home examples' });

describe('admin: home examples', () => {
  it('lists each example with its check in words, and warns when a kind has too few shown', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/home-examples': () => [
        example(),
        example({
          id: '33333333-3333-5333-8333-333333333333',
          label: 'Vector database',
          path: 'BUILD_WITH_AI',
          check: { outcome: 'WEAK', bestRank: 7, resultCount: 20, checkedAt: null },
        }),
      ],
    });
    renderAdmin('/admin/examples');
    const rows = within(await list()).getAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('Shown · expected result at #2');
    expect(rows[0]).toHaveTextContent('DeepL Translator');
    expect(rows[1]).toHaveTextContent('Hidden · best expected result at #7');
    expect(
      screen.getByText(/Shown now: 1 for people who use AI, 0 for builders/),
    ).toBeInTheDocument();
    expect(screen.getByText(/Fewer than 2 shown for Builders/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Examples' })).toHaveAttribute('aria-current', 'page');
  });

  it('keeps the check filter in the address and asks only for those', async () => {
    const m = mockApi({ 'GET /me': () => ADMIN, 'GET /admin/home-examples': () => [] });
    const { params } = renderAdmin('/admin/examples');
    await screen.findByText('No examples here.');
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Check' }), 'Hidden');
    await waitFor(() => expect(params().get('outcome')).toBe('FAIL'));
    await waitFor(() =>
      expect(
        m
          .callsTo('GET', '/admin/home-examples')
          .some((c) => new URLSearchParams(c.search).get('outcome') === 'FAIL'),
      ).toBe(true),
    );
  });

  it('adds an example with an expected result found in the catalog', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/home-examples': () => [],
      'GET /search': () =>
        makeSearch({ results: [makeCard({ id: DEEPL, title: 'DeepL Translator' })] }),
      'POST /admin/home-examples': () => json(example(), 201),
    });
    renderAdmin('/admin/examples');
    await screen.findByText('No examples here.');
    await userEvent.type(screen.getByRole('textbox', { name: /Label on the chip/ }), 'Translate');
    await userEvent.type(
      screen.getByRole('textbox', { name: /What it searches/ }),
      'translate documents',
    );
    await userEvent.type(screen.getByRole('searchbox', { name: 'Search the catalog' }), 'deepl');
    await userEvent.click(screen.getByRole('button', { name: 'Find' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Add DeepL Translator' }));
    await userEvent.click(screen.getByRole('button', { name: 'Add example' }));
    await waitFor(() => expect(m.callsTo('POST', '/admin/home-examples')).toHaveLength(1));
    expect(m.callsTo('POST', '/admin/home-examples')[0]?.body).toEqual({
      label: 'Translate',
      query: 'translate documents',
      path: 'USE_AI',
      expectedResourceIds: [DEEPL],
    });
  });

  it('says what rule a refused example breaks, in the server’s words', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/home-examples': () => [example()],
      'PUT /admin/home-examples/22222222-2222-5222-8222-222222222222': () =>
        new Response(
          JSON.stringify({
            type: 'https://helper2.ai/problems/invalid-request',
            title: 'Invalid request',
            status: 400,
            detail: 'query: no web addresses.',
            code: 'invalid-request',
          }),
          { status: 400, headers: { 'content-type': 'application/problem+json' } },
        ),
    });
    renderAdmin('/admin/examples');
    await userEvent.click(
      within(await list()).getByRole('button', { name: 'Pause Translate documents' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Not saved: query: no web addresses.',
    );
  });

  it('reloads when someone changed the example first', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/home-examples': () => [example()],
      'PUT /admin/home-examples/22222222-2222-5222-8222-222222222222': () =>
        new Response(
          JSON.stringify({
            status: 409,
            title: 'Changed by someone else',
            code: 'version-conflict',
          }),
          { status: 409, headers: { 'content-type': 'application/problem+json' } },
        ),
    });
    renderAdmin('/admin/examples');
    await userEvent.click(
      within(await list()).getByRole('button', { name: 'Pause Translate documents' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Someone changed this example first',
    );
    await waitFor(() => expect(m.callsTo('GET', '/admin/home-examples').length).toBeGreaterThan(2));
  });

  it('closes the editor after a conflict, so the next change sends the stored version', async () => {
    let stored = example();
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/home-examples': () => [stored],
      'PUT /admin/home-examples/22222222-2222-5222-8222-222222222222': () => {
        stored = example({ version: 4 });
        return new Response(
          JSON.stringify({ status: 409, title: 'Changed', code: 'version-conflict' }),
          { status: 409, headers: { 'content-type': 'application/problem+json' } },
        );
      },
    });
    renderAdmin('/admin/examples');
    await userEvent.click(
      within(await list()).getByRole('button', { name: 'Edit Translate documents' }),
    );
    expect(
      screen.getByRole('heading', { name: 'Change “Translate documents”' }),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save change' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Someone changed this example first',
    );
    expect(await screen.findByRole('heading', { name: 'Add an example' })).toBeInTheDocument();
    const put = m.callsTo('PUT', '/admin/home-examples/22222222-2222-5222-8222-222222222222');
    expect(put).toHaveLength(1);
  });

  it('asks before removing, with focus on Cancel', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/home-examples': () => [example()],
      'DELETE /admin/home-examples/22222222-2222-5222-8222-222222222222': () =>
        new Response(null, { status: 204 }),
    });
    renderAdmin('/admin/examples');
    const l = await list();
    await userEvent.click(within(l).getByRole('button', { name: 'Remove Translate documents' }));
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    expect(
      m.callsTo('DELETE', '/admin/home-examples/22222222-2222-5222-8222-222222222222'),
    ).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: 'Yes, remove' }));
    await waitFor(() =>
      expect(
        m.callsTo('DELETE', '/admin/home-examples/22222222-2222-5222-8222-222222222222'),
      ).toHaveLength(1),
    );
  });

  it('is not shown, and not asked, without the admin role', async () => {
    const m = mockApi({ 'GET /me': () => ({ ...ADMIN, roles: ['USER'] }) });
    renderAdmin('/admin/examples');
    expect(
      await screen.findByText('You need an administrator account to see this page.'),
    ).toBeInTheDocument();
    expect(m.callsTo('GET', '/admin/home-examples')).toHaveLength(0);
  });
  it('drafts examples with AI and links to the drafts in the review queue', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/home-examples': () => [example()],
      'POST /admin/home-examples/drafts': () => json({ drafted: 3, model: 'nemotron' }, 202),
    });
    renderAdmin('/admin/examples');
    await list();
    await userEvent.click(screen.getByRole('button', { name: 'Draft examples with AI' }));
    expect(await screen.findByText(/3 drafts wait in the Review queue/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open the drafts' })).toHaveAttribute(
      'href',
      '/admin/review?kind=EXAMPLE_CANDIDATE',
    );
    expect(m.callsTo('POST', '/admin/home-examples/drafts')).toHaveLength(1);
  });

  it('says why there is no draft', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/home-examples': () => [example()],
      'POST /admin/home-examples/drafts': () =>
        new Response(
          JSON.stringify({
            status: 503,
            code: 'unavailable',
            detail: "Today's draft runs are used up. Try again tomorrow (UTC).",
          }),
          { status: 503, headers: { 'content-type': 'application/problem+json' } },
        ),
    });
    renderAdmin('/admin/examples');
    await list();
    await userEvent.click(screen.getByRole('button', { name: 'Draft examples with AI' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      "No drafts now: Today's draft runs are used up.",
    );
  });

  it('shows a drafted example in the review queue as words, marked as AI-drafted', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => ({
        items: [
          {
            id: '44444444-4444-5444-8444-444444444444',
            kind: 'EXAMPLE_CANDIDATE',
            status: 'OPEN',
            reason: 'Drafted example: Translate documents',
            details: '{"label": "Translate documents"}',
            draft: {
              label: 'Translate documents',
              query: 'translate documents',
              path: 'USE_AI',
              model: 'nemotron',
            },
            resourceId: DEEPL,
            resourceTitle: 'DeepL Translator',
            createdAt: '2026-11-05T01:00:00Z',
          },
        ],
        total: 1,
      }),
    });
    renderAdmin('/admin/review?kind=EXAMPLE_CANDIDATE');
    const draft = (await screen.findByText('Searches')).closest('dl') as HTMLElement;
    expect(draft).toHaveAttribute('data-ai-generated', 'true');
    expect(draft).toHaveAttribute('data-ai-model', 'nemotron');
    expect(draft).toHaveTextContent('translate documents');
    expect(draft).toHaveTextContent('People who use AI');
    expect(draft).toHaveTextContent('Drafted with AI (nemotron)');
  });

  it('shows the details as text when the item holds no readable draft', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => ({
        items: [
          {
            id: '55555555-5555-5555-8555-555555555555',
            kind: 'EXAMPLE_CANDIDATE',
            status: 'OPEN',
            reason: 'Drafted example: broken',
            details: '{"label": "Broken"}',
            draft: null,
            resourceId: DEEPL,
            resourceTitle: 'DeepL Translator',
            createdAt: '2026-11-05T01:00:00Z',
          },
        ],
        total: 1,
      }),
    });
    renderAdmin('/admin/review?kind=EXAMPLE_CANDIDATE');
    expect(await screen.findByText('{"label": "Broken"}')).toBeInTheDocument();
    expect(screen.queryByText('Searches')).not.toBeInTheDocument();
  });
});
