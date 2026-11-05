import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GuideResponse, ResultCard } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { makeCard, makeDetail, makeSearch, makeStatus } from '@/test/fixtures';
import { mockApi, problem, sse } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { mockViewport } from '@/test/viewport';

const A = makeCard({
  id: 'aaaaaaaa-aaaa-5aaa-8aaa-aaaaaaaaaaaa',
  title: 'Alpha Search',
  url: 'https://www.alpha.example/x',
  license: null,
});
const B = makeCard({
  id: 'bbbbbbbb-bbbb-5bbb-8bbb-bbbbbbbbbbbb',
  title: 'Postgres MCP',
  type: 'MCP_SERVER',
  url: 'https://github.com/acme/postgres-mcp',
  description:
    'An MCP server that gives agents read-only access to a PostgreSQL database. Easy to set up.',
});
const results: ResultCard[] = [A, B];
const search = () =>
  makeSearch({
    results,
    total: 2,
    sources: [
      makeStatus(),
      makeStatus({
        sourceId: 'npm',
        name: 'npm',
        servedFrom: 'CACHE',
        cachedAt: '2026-10-22T03:00:00Z',
      }),
    ],
  });

function guide(): GuideResponse {
  return {
    status: 'OK',
    searchQuery: 'postgres mcp',
    candidatesConsidered: 2,
    rejected: 0,
    sources: [],
    notices: [],
    generatedAt: '2026-10-25T10:00:00Z',
    prompt: null,
    model: {
      provider: 'nebius-token-factory',
      fastModel: 'f',
      reasoningModel: 'nvidia/nemotron-3-super',
    },
    recommendations: [
      {
        resourceId: B.id,
        title: B.title,
        url: B.url,
        type: 'MCP_SERVER',
        reason: 'Read-only access fits an agent that only reads.',
        quote: 'gives agents read-only access to a PostgreSQL database',
        sourceIds: ['github'],
        aiReasoned: true,
        reasonCheck: 'NOT_CHECKED',
      },
    ],
  };
}

const C = makeCard({
  id: 'cccccccc-cccc-5ccc-8ccc-cccccccccccc',
  title: 'Gamma Tool',
  url: 'https://gamma.example',
});
const three = () => makeSearch({ results: [A, C, B], total: 3, sources: [makeStatus()] });

/** The link of a result row (its title). */
const row = (name: string) =>
  screen
    .getAllByRole('link', { name: new RegExp(name) })
    .find((b) => b.classList.contains('h2-row__title'))!;
/** The whole row (the list item) of a result. */
const rowOf = (name: string) => row(name).closest('li')!;
const rowTitles = () => [...document.querySelectorAll('.h2-row__title')].map((e) => e.textContent);
/** Holds the guide's answer until the test sends it, as the real answer comes about 15 seconds after the results. */
function heldGuide() {
  let send: (r: Response) => void = () => undefined;
  return {
    handler: () =>
      new Promise<Response>((r) => {
        send = r;
      }),
    answer: async (g: GuideResponse) =>
      act(async () => {
        send(sse(g));
      }),
  };
}

describe('results: one column, a row opens the resource page', () => {
  it('shows rows with type, host, date, description and source; nothing is selected and there is no preview', async () => {
    mockApi({ 'GET /search': () => search() });
    renderApp('/search?q=postgres');
    await screen.findByText('Postgres MCP');
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(document.querySelector('.h2-list [aria-current]')).toBeNull();
    const r = within(rowOf('Postgres MCP'));
    expect(r.getByText('MCP server')).toBeInTheDocument();
    expect(r.getByText('github.com')).toBeInTheDocument();
    expect(r.getByText(/^updated /)).toBeInTheDocument();
    expect(r.getByText(/An MCP server that gives agents read-only access/)).toBeInTheDocument();
    expect(r.getByText(/^From /)).toBeInTheDocument();
    expect(screen.queryByText(/PUBLISHED/i)).not.toBeInTheDocument();
  });

  it('the heading says "All results" over the command line with the total from the API', async () => {
    mockApi({ 'GET /search': () => ({ ...search(), total: 104 }) });
    renderApp('/search?q=postgres');
    const h = await screen.findByRole('heading', { level: 2, name: /All results/ });
    expect(h).toHaveTextContent('All results $ helper2 search · 104');
    // The command is decoration: the heading is read as "All results 104".
    expect(h.querySelector('[aria-hidden="true"]')).toHaveTextContent('$ helper2 search ·');
  });

  it('the timeline heading names its own command', async () => {
    mockApi({ 'GET /search': () => search() });
    renderApp('/search?q=postgres&view=timeline');
    expect(await screen.findByRole('heading', { level: 2, name: /All results/ })).toHaveTextContent(
      '$ helper2 search --timeline · 2',
    );
  });

  it('a row title is a real link to the resource page, named by the title only; a click opens it, Back comes back', async () => {
    mockApi({
      'GET /search': () => search(),
      [`GET /resources/${B.id}`]: () => makeDetail({ card: B }),
    });
    const { router } = renderApp('/search?q=postgres');
    await screen.findByText('Postgres MCP');
    expect(row('Alpha Search')).toHaveAttribute('href', `/resource/${A.id}`);
    expect(row('Postgres MCP')).toHaveAccessibleName('Postgres MCP');
    await userEvent.click(row('Postgres MCP'));
    await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' });
    expect(router.state.location.state).toMatchObject({ from: '/search?q=postgres' });
    await userEvent.click(screen.getByRole('link', { name: /Back to results/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(router.state.historyAction).toBe('POP');
    expect(await screen.findByText('Alpha Search')).toBeInTheDocument();
  });

  it('the guide answer comes later: the rows keep the search order, and the pick is marked where it stands', async () => {
    const g = heldGuide();
    mockApi({ 'GET /search': () => three(), 'POST /guide/stream': g.handler });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    expect(
      await screen.findByText('Guide is reading the results… picks will appear here'),
    ).toBeInTheDocument();
    await screen.findByText('Postgres MCP');
    expect(rowTitles()).toEqual(['Alpha Search', 'Gamma Tool', 'Postgres MCP']);
    // jsdom cannot measure: the slot that keeps the row of "How the guide decided" stands in for it.
    expect(document.querySelector('.h2-results__full-slot')).toBeInTheDocument();
    row('Alpha Search').focus();
    await g.answer(guide());
    await screen.findByRole('link', { name: '#1 Postgres MCP' });
    expect(screen.getByText('How the guide decided')).toBeInTheDocument();
    expect(rowTitles()).toEqual(['Alpha Search', 'Gamma Tool', 'Postgres MCP']);
    expect(within(rowOf('Postgres MCP')).getByText('Guide pick #1')).toBeInTheDocument();
    expect(within(rowOf('Alpha Search')).queryByText(/Guide pick/)).not.toBeInTheDocument();
    expect(row('Alpha Search')).toHaveFocus();
    // No announcement that anything moved: nothing did.
    expect(document.querySelector('.h2-list [role="status"], .h2-list [aria-live]')).toBeNull();
  });

  it("a picked row shows the guide's reason, AI-written, and its quote highlighted in the source's words", async () => {
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': () => sse(guide()) });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('link', { name: '#1 Postgres MCP' });
    const r = within(rowOf('Postgres MCP'));
    const reason = r.getByText('Read-only access fits an agent that only reads.');
    expect(reason).toHaveAttribute('data-ai-generated', 'true');
    expect(reason.previousElementSibling).toHaveTextContent('AI-written');
    const quote = r.getByText('gives agents read-only access to a PostgreSQL database');
    expect(quote.tagName).toBe('MARK');
    expect(quote.closest('[data-ai-generated]')).toBeNull();
    expect(r.getByText('Checked word for word in the source')).toBeInTheDocument();
  });

  it('a pick chip on this page jumps to its row; it keeps its full title, however long', async () => {
    const long = 'davidmoserai/AzureDocumentIntelligenceChunkerForVeryLongPdfDocuments';
    mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () =>
        sse({ ...guide(), recommendations: [{ ...guide().recommendations[0]!, title: long }] }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    const chip = await screen.findByRole('link', { name: `#1 ${long}` });
    expect(chip).toHaveAttribute('title', long);
    expect(chip).toHaveAttribute('href', `#result-${B.id}`);
    expect(document.getElementById(`result-${B.id}`)).toBe(rowOf('Postgres MCP'));
  });

  it('a pick passes its reason and checks on to the resource page, which says why the guide picked it', async () => {
    mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () =>
        sse({
          ...guide(),
          recommendations: [{ ...guide().recommendations[0]!, reasonCheck: 'SUPPORTED' }],
        }),
      [`GET /resources/${B.id}`]: () => makeDetail({ card: B }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('link', { name: '#1 Postgres MCP' });
    await userEvent.click(row('Postgres MCP'));
    const why = await screen.findByRole('region', { name: /Why the guide picked it/ });
    const reason = within(why).getByText('Read-only access fits an agent that only reads.');
    expect(reason).toHaveAttribute('data-ai-generated', 'true');
    expect(
      within(why).getByText('A second model call checked this reason against the quote.'),
    ).toBeInTheDocument();
    expect(screen.getByText('gives agents read-only access to a PostgreSQL database').tagName).toBe(
      'MARK',
    );
  });

  it('a pick whose reason the check removed shows no lone "AI-written" label in its row', async () => {
    mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () =>
        sse({
          ...guide(),
          recommendations: [{ ...guide().recommendations[0]!, reason: '', reasonCheck: 'REMOVED' }],
        }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('link', { name: '#1 Postgres MCP' });
    expect(within(rowOf('Postgres MCP')).queryByText('AI-written')).not.toBeInTheDocument();
    expect(document.querySelectorAll('.h2-list [data-ai-generated]')).toHaveLength(0);
  });

  it('an AI-written description in a row is labelled and marked machine-readable', async () => {
    const ai = makeCard({
      id: 'dddddddd-dddd-5ddd-8ddd-dddddddddddd',
      title: 'Summed Up',
      description: 'A short summary.',
      descriptionOrigin: 'AI_SUMMARY',
    });
    mockApi({
      'GET /search': () => makeSearch({ results: [A, ai], total: 2, sources: [makeStatus()] }),
    });
    renderApp('/search?q=postgres+mcp');
    await screen.findByText('Summed Up');
    const r = within(rowOf('Summed Up'));
    const text = r.getByText('A short summary.');
    expect(text).toHaveAttribute('data-ai-generated', 'true');
    expect(text.previousElementSibling).toHaveTextContent('AI summary');
  });

  it('a pick on an AI-written description shows it labelled and marked, never as the checked quote', async () => {
    const aiB = { ...B, descriptionOrigin: 'AI_EXTRACTED' as const };
    mockApi({
      'GET /search': () => makeSearch({ results: [A, aiB], total: 2, sources: [makeStatus()] }),
      'POST /guide/stream': () => sse(guide()),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('link', { name: '#1 Postgres MCP' });
    const r = rowOf('Postgres MCP');
    expect(r.querySelector('mark')).toBeNull();
    expect(within(r).getByText(/Easy to set up\./)).toHaveAttribute('data-ai-generated', 'true');
    expect(within(r).getByText('Extracted by AI')).toBeInTheDocument();
    expect(within(r).queryByText('Checked word for word in the source')).not.toBeInTheDocument();
  });

  it('keeps a line for "web searches left" while the guide reads, and the count takes its place', async () => {
    let answer: (r: Response) => void = () => undefined;
    mockApi({
      'GET /search': () => ({ ...search(), webSearches: { limit: 3, remaining: 3 } }),
      'POST /guide/stream': () =>
        new Promise<Response>((r) => {
          answer = r;
        }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByText('Postgres MCP');
    const blank = document.querySelector('.h2-results__guide .h2-web-left');
    expect(blank).toHaveAttribute('aria-hidden', 'true');
    expect(blank?.textContent?.trim()).toBe('');
    await act(async () => {
      answer(sse({ ...guide(), webSearches: { limit: 3, remaining: 2 } }));
    });
    expect(
      await screen.findByText(/2 of 3 web searches left today for the guide/),
    ).toBeInTheDocument();
    expect(document.querySelectorAll('.h2-results__guide .h2-web-left')).toHaveLength(1);
  });

  it('keeps no line for "web searches left" when the limit is a test ceiling (staging), so nothing moves either way', async () => {
    let answer: (r: Response) => void = () => undefined;
    mockApi({
      'GET /search': () => ({ ...search(), webSearches: { limit: 1000, remaining: 999 } }),
      'POST /guide/stream': () =>
        new Promise<Response>((r) => {
          answer = r;
        }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByText('Postgres MCP');
    expect(document.querySelector('.h2-web-left')).toBeNull();
    await act(async () => {
      answer(sse({ ...guide(), webSearches: { limit: 1000, remaining: 998 } }));
    });
    await screen.findByRole('link', { name: '#1 Postgres MCP' });
    expect(document.querySelector('.h2-web-left')).toBeNull();
  });

  it('an answer with no AI pick says so once, in the bar, with the next steps there; no row is a pick', async () => {
    const plain = { ...guide().recommendations[0]!, aiReasoned: false };
    mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () =>
        sse({ ...guide(), constraints: ['open source'], recommendations: [plain] }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    const bar = await screen.findByRole('region', { name: 'Guide picks' });
    expect(await within(bar).findByText('Nothing clearly fits your need.')).toBeInTheDocument();
    expect(bar).toHaveClass('is-none');
    expect(screen.getAllByText('Nothing clearly fits your need.')).toHaveLength(1);
    expect(
      within(bar).getByRole('button', { name: 'Ask without “open source”' }),
    ).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Rephrase' })).toBeInTheDocument();
    expect(screen.getByText('How the guide decided').closest('details')).not.toHaveAttribute(
      'open',
    );
    expect(rowTitles()).toEqual(['Alpha Search', 'Postgres MCP']);
    expect(screen.queryByText(/Guide pick #/)).not.toBeInTheDocument();
  });

  it('type tabs: All, the four types with most results, and More with the rest', async () => {
    const type = [
      { value: 'LIBRARY', count: 40 },
      { value: 'PRODUCT', count: 31 },
      { value: 'MODEL', count: 22 },
      { value: 'MCP_SERVER', count: 6 },
      { value: 'PAPER', count: 5 },
      { value: 'DATASET', count: 2 },
    ];
    mockApi({ 'GET /search': () => ({ ...search(), facets: { ...search().facets, type } }) });
    const { params } = renderApp('/search?q=postgres');
    const tabs = within(await screen.findByRole('group', { name: 'Result types' }));
    expect(tabs.getByRole('button', { name: 'All 2' })).toHaveAttribute('aria-pressed', 'true');
    expect(tabs.getByRole('button', { name: 'Library 40' })).toBeVisible();
    expect(tabs.getByRole('button', { name: 'MCP server 6' })).toBeVisible();
    const more = tabs.getByRole('button', { name: /More/ });
    expect(more).toHaveAttribute('aria-expanded', 'false');
    expect(tabs.queryByRole('button', { name: 'Paper 5' })).not.toBeInTheDocument();
    await userEvent.click(more);
    await userEvent.click(tabs.getByRole('button', { name: 'Paper 5' }));
    await waitFor(() => expect(params().getAll('type')).toEqual(['PAPER']));
    // The type chosen under More shows as a tab, pressed.
    expect(
      await within(screen.getByRole('group', { name: 'Result types' })).findByRole('button', {
        name: 'Paper 5',
        pressed: true,
      }),
    ).toBeInTheDocument();
  });

  it('shows the filters in place under the heading on a wide screen', async () => {
    mockApi({ 'GET /search': () => search() });
    renderApp('/search?q=postgres');
    const toggle = await screen.findByRole('button', { name: 'Filters' });
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('complementary', { name: 'Filters' })).toBeVisible();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  describe('on a phone', () => {
    beforeEach(() => mockViewport(390));
    afterEach(() => vi.unstubAllGlobals());

    it('nothing is selected on load, and the filters open as a drawer that gives focus back when it closes', async () => {
      mockApi({ 'GET /search': () => search() });
      renderApp('/search?q=postgres');
      await screen.findByText('Postgres MCP');
      expect(document.querySelector('.h2-list [aria-current]')).toBeNull();
      const toggle = screen.getByRole('button', { name: 'Filters' });
      await userEvent.click(toggle);
      const drawer = await screen.findByRole('dialog', { name: 'Filters' });
      expect(within(drawer).getByRole('button', { name: 'Close' })).toHaveFocus();
      expect(within(drawer).getByRole('button', { name: 'Show 2 results' })).toBeInTheDocument();
      await userEvent.keyboard('{Escape}');
      expect(toggle).toHaveFocus();
      expect(toggle).toHaveAttribute('aria-expanded', 'false');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  it('Try again on a failed guide keeps the reader there: focus moves to the picks heading, not the page body', async () => {
    let n = 0;
    mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () =>
        n++ === 0 ? problem(502, 'Bad gateway', 'The model timed out.') : sse(guide()),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    expect(await screen.findByRole('alert')).toHaveTextContent('The model timed out.');
    screen.getByRole('button', { name: /Try again/ }).focus();
    await userEvent.keyboard('{Enter}');
    expect(await screen.findByRole('link', { name: '#1 Postgres MCP' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Guide picks' })).toHaveFocus();
  });

  it('has one h1 and no axe violations', async () => {
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': () => sse(guide()) });
    const { container } = renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('link', { name: '#1 Postgres MCP' });
    await userEvent.click(screen.getByText('How the guide decided'));
    await userEvent.click(screen.getByRole('button', { name: 'Filters' }));
    await settle();
    expect(screen.getAllByRole('button', { name: 'Filters' })).toHaveLength(1);
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(await axeViolations(container)).toEqual([]);
  });

  it('with no pick and no model the bar says so in one line', async () => {
    mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () => sse({ ...guide(), model: null, recommendations: [] }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    expect(
      await screen.findByText('No pick: no result had a sentence that proves it'),
    ).toBeInTheDocument();
  });

  it('"Ask without" asks the guide again without that requirement, the same way as ✕ in "Understood as"', async () => {
    const plain = { ...guide().recommendations[0]!, aiReasoned: false };
    const m = mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () =>
        sse({ ...guide(), constraints: ['open source', 'free'], recommendations: [plain] }),
    });
    const { params } = renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    const bar = await screen.findByRole('region', { name: 'Guide picks' });
    const button = await within(bar).findByRole('button', { name: 'Ask without “free”' });
    // The requirement is the planner's text: marked in the HTML, labelled once on screen (ADR 0038).
    expect(within(button).getByText('free')).toHaveAttribute('data-ai-generated', 'true');
    expect(within(bar).getByText('Read by AI')).toBeInTheDocument();
    await userEvent.click(button);
    await waitFor(() => expect(params().getAll('drop')).toEqual(['constraint:free']));
    await waitFor(() =>
      expect(m.callsTo('POST', '/guide/stream')[1]?.body).toEqual({
        request: 'postgres mcp',
        drop: ['constraint:free'],
      }),
    );
  });

  it('"Rephrase" puts the cursor in the box', async () => {
    const plain = { ...guide().recommendations[0]!, aiReasoned: false };
    mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () => sse({ ...guide(), recommendations: [plain] }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await userEvent.click(await screen.findByRole('button', { name: 'Rephrase' }));
    expect(screen.getByLabelText('Search AI resources')).toHaveFocus();
  });

  it('the fold holds only how the guide decided: the steps and the reading, no second list of results', async () => {
    const plain = { ...guide().recommendations[0]!, aiReasoned: false };
    mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () =>
        sse({ ...guide(), constraints: ['open source'], recommendations: [plain] }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    const summary = await screen.findByText('How the guide decided');
    await userEvent.click(summary);
    const fold = summary.closest('details') as HTMLElement;
    expect(fold).toHaveAttribute('open');
    const trace = within(fold).getByRole('region', { name: 'How the guide decided' });
    expect(
      within(trace).getByRole('list', { name: 'How this answer was made' }),
    ).toBeInTheDocument();
    expect(
      within(trace).getByRole('button', { name: 'Remove “open source” and ask again' }),
    ).toBeInTheDocument();
    expect(within(fold).queryByRole('link', { name: 'Postgres MCP' })).not.toBeInTheDocument();
    expect(within(fold).queryByText('Nothing clearly fits your need.')).not.toBeInTheDocument();
    expect(within(fold).queryByRole('heading')).not.toBeInTheDocument();
  });

  it('with picks, the fold has the steps but none of the picks again', async () => {
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': () => sse(guide()) });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    const fold = (await screen.findByText('How the guide decided')).closest(
      'details',
    ) as HTMLElement;
    expect(
      within(fold).getByText('All AI suggestions passed the evidence check.'),
    ).toBeInTheDocument();
    expect(within(fold).queryByText('Postgres MCP')).not.toBeInTheDocument();
    expect(
      within(fold).queryByText('Read-only access fits an agent that only reads.'),
    ).not.toBeInTheDocument();
  });

  it('a pick that is not on this page of results links to its own page', async () => {
    const away = {
      ...guide().recommendations[0]!,
      resourceId: 'cccccccc-cccc-5ccc-8ccc-cccccccccccc',
      title: 'Elsewhere MCP',
    };
    mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () => sse({ ...guide(), recommendations: [away] }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    const chip = await screen.findByRole('link', { name: /#1\s*Elsewhere MCP/ });
    expect(chip).toHaveAttribute('href', '/resource/cccccccc-cccc-5ccc-8ccc-cccccccccccc');
  });

  it('the timeline view shows more too', async () => {
    mockApi({
      'GET /search': () => makeSearch({ results, total: 120, size: 50, sources: [makeStatus()] }),
    });
    renderApp('/search?q=postgres+mcp&view=timeline');
    expect(await screen.findByRole('button', { name: 'Show more' })).toBeInTheDocument();
  });

  it('shows the sources that answered at the bottom', async () => {
    mockApi({ 'GET /search': () => search() });
    renderApp('/search?q=postgres');
    expect(
      await screen.findByRole('heading', { name: /2 of 2 sources answered/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Sources searched' })).toBeInTheDocument();
  });
});
