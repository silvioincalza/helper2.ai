import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GuideResponse, ResultCard } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { makeCard, makeSearch, makeStatus } from '@/test/fixtures';
import { mockApi, problem, sse } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

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

const row = (name: string) =>
  screen
    .getAllByRole('button', { name: new RegExp(name) })
    .find((b) => b.classList.contains('h2-select-row'))!;
const titlesIn = (list: HTMLElement) =>
  [...list.querySelectorAll('.h2-select-row .h2-row__title')].map((e) => e.textContent);
const previewTitle = () =>
  within(screen.getByRole('article')).getByRole('heading', { level: 2 }).textContent;
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
const rowTitles = () =>
  [...document.querySelectorAll('.h2-select-row .h2-row__title')].map((e) => e.textContent);

describe('results: list and preview', () => {
  it('selects the first result and shows its preview; a click selects another and writes its id to the URL', async () => {
    mockApi({ 'GET /search': () => search() });
    const { params } = renderApp('/search?q=postgres');
    const preview = await screen.findByRole('article');
    expect(
      within(preview).getByRole('heading', { level: 2, name: 'Alpha Search' }),
    ).toBeInTheDocument();
    expect(within(preview).getByText('Not stated')).toBeInTheDocument();
    await userEvent.click(row('Postgres MCP'));
    await waitFor(() => expect(params().get('id')).toBe(B.id));
    expect(
      within(screen.getByRole('article')).getByRole('heading', { level: 2, name: 'Postgres MCP' }),
    ).toBeInTheDocument();
    expect(row('Postgres MCP')).toHaveAttribute('aria-current', 'true');
  });

  it('opens with the result named by id in the URL, and an unknown id selects the first', async () => {
    mockApi({ 'GET /search': () => search() });
    renderApp(`/search?q=postgres&id=${B.id}`);
    expect(
      within(await screen.findByRole('article')).getByRole('heading', { level: 2 }),
    ).toHaveTextContent('Postgres MCP');
  });

  it('an unknown id selects the first result', async () => {
    mockApi({ 'GET /search': () => search() });
    renderApp('/search?q=postgres&id=not-here');
    expect(
      within(await screen.findByRole('article')).getByRole('heading', { level: 2 }),
    ).toHaveTextContent('Alpha Search');
  });

  it('the guide answer arrives later: the list above stays put, the picks move to the top in one update and the preview opens on pick #1', async () => {
    const g = heldGuide();
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': g.handler });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    expect(
      await screen.findByText('Guide is reading the results… picks will appear here'),
    ).toBeInTheDocument();
    await screen.findByRole('article');
    expect(rowTitles()).toEqual(['Alpha Search', 'Postgres MCP']);
    expect(previewTitle()).toBe('Alpha Search');
    // jsdom cannot measure: the slot that keeps the row of "How the guide decided" stands in for it while the guide
    // reads, so what is above the list keeps its height when the answer comes.
    expect(document.querySelector('.h2-results__full-slot')).toBeInTheDocument();
    expect(screen.queryByText('How the guide decided')).not.toBeInTheDocument();
    // A keyboard user is on the first row when the answer comes.
    row('Alpha Search').focus();
    await g.answer(guide());
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    expect(screen.getByText('How the guide decided')).toBeInTheDocument();
    expect(document.querySelector('.h2-results__full-slot')).not.toBeInTheDocument();
    expect(rowTitles()).toEqual(['Postgres MCP', 'Alpha Search']);
    expect(titlesIn(screen.getByRole('list', { name: 'Guide picks' }))).toEqual(['Postgres MCP']);
    expect(titlesIn(screen.getByRole('list', { name: 'More results' }))).toEqual(['Alpha Search']);
    expect(screen.getByText('Pick #1')).toBeInTheDocument();
    expect(previewTitle()).toBe('Postgres MCP');
    expect(row('Postgres MCP')).toHaveAttribute('aria-current', 'true');
    // Focus stays on the row it was on, wherever the row went.
    expect(row('Alpha Search')).toHaveFocus();
  });

  it('says once, politely, that the picks moved to the top', async () => {
    const g = heldGuide();
    mockApi({ 'GET /search': () => three(), 'POST /guide/stream': g.handler });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('article');
    const list = document.querySelector('.h2-list') as HTMLElement;
    const status = within(list).getByRole('status');
    expect(status).toBeEmptyDOMElement();
    const second = { ...guide().recommendations[0]!, resourceId: C.id, title: C.title };
    await g.answer({ ...guide(), recommendations: [...guide().recommendations, second] });
    await screen.findByRole('button', { name: '#2 Gamma Tool' });
    expect(within(list).getAllByRole('status')).toHaveLength(1);
    expect(status).toHaveTextContent('The guide’s 2 picks are now at the top of the list.');
    expect(list.querySelectorAll('[aria-live], [role="status"]')).toHaveLength(2);
  });

  it("shows the picks in the guide's order, then the other results in search order, each result once", async () => {
    const second = { ...guide().recommendations[0]!, resourceId: A.id, title: A.title };
    mockApi({
      'GET /search': () => three(),
      'POST /guide/stream': () =>
        sse({ ...guide(), recommendations: [...guide().recommendations, second] }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('button', { name: '#2 Alpha Search' });
    expect(titlesIn(screen.getByRole('list', { name: 'Guide picks' }))).toEqual([
      'Postgres MCP',
      'Alpha Search',
    ]);
    expect(titlesIn(screen.getByRole('list', { name: 'More results' }))).toEqual(['Gamma Tool']);
    expect(rowTitles()).toEqual(['Postgres MCP', 'Alpha Search', 'Gamma Tool']);
  });

  it('an id in the URL wins over pick #1: a shared link or a reload opens the result it names', async () => {
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': () => sse(guide()) });
    renderApp(`/search?q=postgres+mcp&ask=postgres+mcp&id=${A.id}`);
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    expect(previewTitle()).toBe('Alpha Search');
    expect(row('Alpha Search')).toHaveAttribute('aria-current', 'true');
  });

  it('a row the user clicked before the answer stays selected when the answer comes', async () => {
    const g = heldGuide();
    mockApi({ 'GET /search': () => three(), 'POST /guide/stream': g.handler });
    const { params } = renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('article');
    await userEvent.click(row('Gamma Tool'));
    await waitFor(() => expect(params().get('id')).toBe(C.id));
    await g.answer(guide());
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    expect(previewTitle()).toBe('Gamma Tool');
    expect(row('Gamma Tool')).toHaveAttribute('aria-current', 'true');
  });

  it("the preview of a pick says why the guide picked it, AI-written, with the source's words as proof", async () => {
    mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () =>
        sse({
          ...guide(),
          recommendations: [{ ...guide().recommendations[0]!, reasonCheck: 'SUPPORTED' }],
        }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    const pv = within(screen.getByRole('article'));
    expect(pv.getByRole('heading', { name: 'Why the guide picked it' })).toBeInTheDocument();
    expect(pv.queryByRole('heading', { name: 'About' })).not.toBeInTheDocument();
    const reason = pv.getByText('Read-only access fits an agent that only reads.');
    expect(reason).toHaveAttribute('data-ai-generated', 'true');
    expect(reason.previousElementSibling).toHaveTextContent('AI-written');
    expect(
      pv.getByText('A second model call checked this reason against the quote.'),
    ).toBeInTheDocument();
    const quote = pv.getByText('gives agents read-only access to a PostgreSQL database');
    expect(quote.tagName).toBe('MARK');
    expect(quote.closest('[data-ai-generated]')).toBeNull();
    // The reason comes first, the proof under it.
    expect(reason.compareDocumentPosition(quote) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('the preview of a result the guide did not pick is "About" it, with its own description, not a judgement', async () => {
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': () => sse(guide()) });
    renderApp(`/search?q=postgres+mcp&ask=postgres+mcp&id=${A.id}`);
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    const pv = within(screen.getByRole('article'));
    expect(pv.getByRole('heading', { name: 'About' })).toBeInTheDocument();
    expect(pv.queryByRole('heading', { name: 'Why the guide picked it' })).not.toBeInTheDocument();
    expect(pv.getByText('Translates documents between languages.')).not.toHaveAttribute(
      'data-ai-generated',
    );
    expect(pv.queryByText('AI-written')).not.toBeInTheDocument();
  });

  it('a pick whose reason the check removed shows no lone "AI-written" label, in the row or the preview', async () => {
    mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () =>
        sse({
          ...guide(),
          recommendations: [{ ...guide().recommendations[0]!, reason: '', reasonCheck: 'REMOVED' }],
        }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    expect(within(row('Postgres MCP')).queryByText('AI-written')).not.toBeInTheDocument();
    const pv = within(screen.getByRole('article'));
    expect(pv.queryByText('AI-written')).not.toBeInTheDocument();
    expect(pv.getByText(/claimed more than the quote shows/)).toBeInTheDocument();
    expect(
      document.querySelectorAll('.h2-list [data-ai-generated], article [data-ai-generated]'),
    ).toHaveLength(0);
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
    await screen.findByRole('article');
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
    await screen.findByRole('article');
    expect(document.querySelector('.h2-web-left')).toBeNull();
    await act(async () => {
      answer(sse({ ...guide(), webSearches: { limit: 1000, remaining: 998 } }));
    });
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    expect(document.querySelector('.h2-web-left')).toBeNull();
  });

  it('a pick chip keeps its full title for screen readers and on hover, however long', async () => {
    const long = 'davidmoserai/AzureDocumentIntelligenceChunkerForVeryLongPdfDocuments';
    mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () =>
        sse({ ...guide(), recommendations: [{ ...guide().recommendations[0]!, title: long }] }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    const chip = await screen.findByRole('button', { name: `#1 ${long}` });
    expect(chip).toHaveAttribute('title', long);
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
    const r = row('Summed Up');
    expect(within(r).getByText('AI summary')).toBeInTheDocument();
    expect(within(r).getByText('A short summary.')).toHaveAttribute('data-ai-generated', 'true');
  });

  it('an AI-written description in the preview is labelled and marked, and never the proof of a pick', async () => {
    const ai = makeCard({
      id: 'dddddddd-dddd-5ddd-8ddd-dddddddddddd',
      title: 'Summed Up',
      description: 'A short summary.',
      descriptionOrigin: 'AI_SUMMARY',
    });
    mockApi({
      'GET /search': () => makeSearch({ results: [A, ai], total: 2, sources: [makeStatus()] }),
    });
    renderApp(`/search?q=postgres+mcp&id=${ai.id}`);
    const pv = within(await screen.findByRole('article'));
    const text = await pv.findByText('A short summary.');
    expect(text).toHaveAttribute('data-ai-generated', 'true');
    expect(text.previousElementSibling).toHaveTextContent('AI summary');
  });

  it('a pick on an AI-written description shows it labelled and marked, not as the proof', async () => {
    const aiB = { ...B, descriptionOrigin: 'AI_EXTRACTED' as const };
    mockApi({
      'GET /search': () => makeSearch({ results: [A, aiB], total: 2, sources: [makeStatus()] }),
      'POST /guide/stream': () => sse(guide()),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    const pv = within(screen.getByRole('article'));
    expect(document.querySelector('article .h2-proof')).toBeNull();
    expect(pv.getByText(/Easy to set up\./)).toHaveAttribute('data-ai-generated', 'true');
    expect(pv.getByText('Extracted by AI')).toBeInTheDocument();
  });

  it('a fact the sources disagree on says so, with each value and its source, not "Not stated"', async () => {
    const split = makeCard({
      id: 'eeeeeeee-eeee-5eee-8eee-eeeeeeeeeeee',
      title: 'Split License',
      license: null,
      conflicts: [
        {
          field: 'license',
          values: [
            { value: 'MIT', sourceId: 'github' },
            { value: 'Apache-2.0', sourceId: 'npm' },
          ],
        },
      ],
    });
    mockApi({
      'GET /search': () => makeSearch({ results: [split], total: 1, sources: [makeStatus()] }),
    });
    renderApp('/search?q=postgres+mcp');
    const preview = await screen.findByRole('article');
    expect(preview.querySelector('dd.is-conflict')).toHaveTextContent(
      'Sources disagree: MIT (github) · Apache-2.0 (npm)',
    );
    expect(preview.querySelector('dd.is-conflict')).not.toHaveTextContent('Not stated');
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

  it('an answer with no AI pick says so once, in the bar, with the next steps there; the list keeps the search order', async () => {
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
    expect(screen.queryByRole('list', { name: 'Guide picks' })).not.toBeInTheDocument();
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

  it('the timeline view pages too', async () => {
    mockApi({
      'GET /search': () => makeSearch({ results, total: 120, size: 50, sources: [makeStatus()] }),
    });
    renderApp('/search?q=postgres+mcp&view=timeline');
    expect(await screen.findByRole('button', { name: /Next/ })).toBeInTheDocument();
  });

  it('up to 860 px the preview opens inside the list, under the selected row, with a link to the full page', async () => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({
        matches: false,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
      })),
    );
    mockApi({ 'GET /search': () => search() });
    renderApp('/search?q=postgres');
    const full = await screen.findByRole('link', { name: 'Full details' });
    expect(full).toHaveAttribute('href', `/resource/${A.id}`);
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(full.closest('li')).toContainElement(row('Alpha Search'));
    vi.unstubAllGlobals();
  });

  it('shows the sources that answered at the bottom', async () => {
    mockApi({ 'GET /search': () => search() });
    renderApp('/search?q=postgres');
    expect(
      await screen.findByRole('heading', { name: /2 of 2 sources answered/ }),
    ).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Sources searched' })).toBeInTheDocument();
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
    expect(await screen.findByRole('button', { name: '#1 Postgres MCP' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Guide picks' })).toHaveFocus();
  });

  it('has one h1 and no axe violations', async () => {
    mockApi({ 'GET /search': () => search(), 'POST /guide/stream': () => sse(guide()) });
    const { container } = renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    await userEvent.click(screen.getByText('How the guide decided'));
    await userEvent.click(screen.getByRole('button', { name: 'Filters' }));
    await settle();
    // One Filters button: the panel has no second toggle inside it.
    expect(screen.getAllByRole('button', { name: 'Filters' })).toHaveLength(1);
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(await axeViolations(container)).toEqual([]);
  });
});
