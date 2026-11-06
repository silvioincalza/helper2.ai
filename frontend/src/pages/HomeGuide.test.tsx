import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GuideResponse } from '@/api/types';
import { mockApi as mockAll, problem, sse, type Handler } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import { axeViolations } from '@/test/a11y';
import { makeCard, makeDetail, makeSearch, makeStatus } from '@/test/fixtures';

/** The guide's pick is on the page of results: its row in the list shows the pick. */
const PICKED = makeCard({
  id: '22222222-2222-5222-8222-222222222222',
  title: 'Postgres MCP',
  type: 'MCP_SERVER',
  url: 'https://github.com/acme/postgres-mcp',
  description: 'MCP server that gives agents read-only access to a PostgreSQL database',
});
const mockApi = (handlers: Record<string, Handler> = {}) =>
  mockAll({
    'GET /search': () => makeSearch({ results: [makeCard(), PICKED], total: 2 }),
    [`GET /resources/${PICKED.id}`]: () => makeDetail({ card: PICKED }),
    ...handlers,
  });
/** The row of the picked result in the list. */
const pickedRow = async () =>
  within(
    (await screen.findAllByRole('link', { name: 'Postgres MCP' }))
      .find((l) => l.classList.contains('h2-row__title'))!
      .closest('li')!,
  );
/** The guide answered: its pick #1 is in the bar. */
const answered = () => screen.findByRole('link', { name: '#1 Postgres MCP' });

function makeGuide(over: Partial<GuideResponse> = {}): GuideResponse {
  return {
    status: 'OK',
    searchQuery: 'mcp server postgres',
    recommendations: [
      {
        resourceId: '22222222-2222-5222-8222-222222222222',
        title: 'Postgres MCP',
        url: 'https://github.com/acme/postgres-mcp',
        type: 'MCP_SERVER',
        reason: 'Lets an agent run read-only SQL against Postgres.',
        quote: 'MCP server that gives agents read-only access to a PostgreSQL database',
        sourceIds: ['github'],
        aiReasoned: true,
        reasonCheck: 'NOT_CHECKED',
      },
    ],
    prompt: null,
    model: {
      provider: 'nebius-token-factory',
      fastModel: 'nvidia/nemotron-nano',
      reasoningModel: 'nvidia/nemotron-3-super',
    },
    aiContent: {
      aiGenerated: true,
      provider: 'nebius-token-factory',
      models: ['nvidia/nemotron-nano', 'nvidia/nemotron-3-super'],
      fields: ['searchQuery', 'recommendations[].reason'],
      policy: '/mission',
    },
    candidatesConsidered: 12,
    rejected: 0,
    sources: [makeStatus()],
    notices: [],
    generatedAt: '2026-10-25T10:00:00Z',
    ...over,
  };
}

const BOX = 'Search AI resources';

async function ask(text: string) {
  await userEvent.type(await screen.findByLabelText(BOX), text);
  await userEvent.click(screen.getByRole('button', { name: 'Ask the guide' }));
}

describe('guide on the one page', () => {
  it('asks with the text in the box, also searches it, and marks pick #1 in its row with its reason and checked quote', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    const { params } = renderApp('/');
    await ask('an MCP server for Postgres');

    await answered();
    expect(m.callsTo('POST', '/guide/stream')[0]?.body).toEqual({
      request: 'an MCP server for Postgres',
    });
    expect(params().get('ask')).toBe('an MCP server for Postgres');
    expect(params().get('q')).toBe('an MCP server for Postgres');
    const r = await pickedRow();
    expect(r.getByRole('link', { name: 'Postgres MCP' })).toHaveAttribute(
      'href',
      '/resource/22222222-2222-5222-8222-222222222222/postgres-mcp',
    );
    expect(r.getByText('Guide pick #1')).toBeInTheDocument();
    expect(r.getByText('Lets an agent run read-only SQL against Postgres.')).toBeInTheDocument();
    expect(r.getByText('Checked word for word in the source')).toBeInTheDocument();
    expect(r.getByText(/read-only access to a PostgreSQL database/).tagName).toBe('MARK');
  });

  it('marks AI-written text machine-readable, and never the quote from the source (EU AI Act Art. 50(2))', async () => {
    mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp('/');
    await ask('an MCP server for Postgres');

    await answered();
    const reasons = screen.getAllByText('Lets an agent run read-only SQL against Postgres.');
    // In the row, labelled on screen and marked in the HTML.
    expect(reasons).toHaveLength(1);
    for (const r of reasons) {
      expect(r).toHaveAttribute('data-ai-generated', 'true');
      expect(r.previousElementSibling).toHaveTextContent('AI-written');
    }
    const trace = document.querySelector('.guide-answer');
    expect(trace).toHaveAttribute('data-ai-provider', 'nebius-token-factory');
    expect(trace).toHaveAttribute('data-ai-models', 'nvidia/nemotron-nano nvidia/nemotron-3-super');
    for (const q of screen.getAllByText(/read-only access to a PostgreSQL database/))
      expect(q.closest('[data-ai-generated]')).toBeNull();
  });

  it('marks the planned search query, not our words around it', async () => {
    mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp('/');
    await ask('an MCP server for Postgres');
    await answered();
    const q = screen.getByText('mcp server postgres');
    expect(q).toHaveAttribute('data-ai-generated', 'true');
    expect(q.parentElement).toHaveTextContent('Searched the catalog for: mcp server postgres');
    expect(q.parentElement).not.toHaveAttribute('data-ai-generated');
  });

  it('the resource page of a pick shows each requirement as confirmed with its quote or as not confirmed, in words', async () => {
    const base = makeGuide();
    mockApi({
      'POST /guide/stream': () =>
        sse(
          makeGuide({
            constraints: ['open source', 'free'],
            recommendations: [
              {
                ...base.recommendations[0]!,
                checks: [
                  {
                    constraint: 'open source',
                    status: 'CONFIRMED',
                    quote: 'MIT licensed, open source',
                  },
                  { constraint: 'free', status: 'NOT_CONFIRMED', quote: null },
                ],
              },
            ],
          }),
        ),
    });
    renderApp('/');
    await ask('a free open-source MCP server for Postgres');
    await answered();
    await userEvent.click((await pickedRow()).getByRole('link', { name: 'Postgres MCP' }));

    const checks = (
      await screen.findByRole('region', { name: /Why the guide picked it/ })
    ).querySelector('ul')!;
    expect(checks).toHaveAccessibleName('Your requirements, checked against the source');
    const items = within(checks).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent(
      'open source: confirmed by the source: MIT licensed, open source',
    );
    expect(items[1]).toHaveTextContent('free: not confirmed by the source');
    // Art. 50: the planner wrote the requirements, so they carry a label over their checks.
    expect(checks.previousElementSibling).toHaveTextContent('Read by AI');
  });

  it('shows no requirement list when the need stated none', async () => {
    mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp('/');
    await ask('an MCP server for Postgres');
    await answered();
    expect(
      screen.queryByRole('list', { name: 'Your requirements, checked against the source' }),
    ).not.toBeInTheDocument();
  });

  it('shows which model planned and which model chose, and on which provider', async () => {
    mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(
      await screen.findByText(
        'Planning: nvidia/nemotron-nano · Choosing: nvidia/nemotron-3-super · on nebius-token-factory',
      ),
    ).toBeInTheDocument();
  });

  it('shows an adapted prompt with its catalog sources and a warning to read it before pasting', async () => {
    mockApi({
      'POST /guide/stream': () =>
        sse(
          makeGuide({
            prompt: {
              text: 'Summarise [notes] into decisions and owners.',
              basedOn: [
                {
                  resourceId: '33333333-3333-5333-8333-333333333333',
                  title: 'Meeting summary prompt',
                },
              ],
            },
          }),
        ),
    });
    renderApp('/');
    await ask('a prompt to summarise meeting notes');
    expect(await screen.findByText('Ready-to-use prompt')).toBeInTheDocument();
    expect(screen.getByText('Summarise [notes] into decisions and owners.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Meeting summary prompt' })).toHaveAttribute(
      'href',
      '/resource/33333333-3333-5333-8333-333333333333/meeting-summary-prompt',
    );
    expect(screen.getByText(/Third parties wrote the prompts it adapts/)).toBeInTheDocument();
  });

  it('LLM_UNAVAILABLE says so in the bar, picks nothing and writes nothing AI', async () => {
    const fallback = { ...makeGuide().recommendations[0]!, aiReasoned: false };
    mockApi({
      'POST /guide/stream': () =>
        sse(
          makeGuide({
            status: 'LLM_UNAVAILABLE',
            model: null,
            aiContent: null,
            prompt: null,
            recommendations: [fallback],
          }),
        ),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');
    // No pick: the bar's one line says why; how the guide decided stays folded, so the results stay in view.
    const bar = await screen.findByRole('region', { name: 'Guide picks' });
    expect(
      await within(bar).findByText(/The AI model is not available right now/),
    ).toBeInTheDocument();
    expect(screen.getByText('How the guide decided').closest('details')).not.toHaveAttribute(
      'open',
    );
    // Asking again without a requirement would hit the same unavailable model: no next steps.
    expect(within(bar).queryByRole('button')).not.toBeInTheDocument();
    expect(screen.queryByText('Pick #1')).not.toBeInTheDocument();
    expect(screen.queryByText('Ready-to-use prompt')).not.toBeInTheDocument();
    expect(screen.queryByText(/^Planning:/)).not.toBeInTheDocument();
    expect(document.querySelector('[data-ai-generated]')).toBeNull();
  });

  it('over the daily quota says so instead of calling the model unavailable', async () => {
    const fallback = { ...makeGuide().recommendations[0]!, aiReasoned: false };
    mockApi({
      'POST /guide/stream': () =>
        sse(
          makeGuide({
            status: 'LLM_UNAVAILABLE',
            model: null,
            recommendations: [fallback],
            notices: [{ code: 'GUIDE_DAILY_QUOTA', message: 'quota used' }],
          }),
        ),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');
    const bar = await screen.findByRole('region', { name: 'Guide picks' });
    expect(await within(bar).findByText(/You have used today's AI answers/)).toBeInTheDocument();
    expect(screen.queryByText(/The AI model is not available right now/)).not.toBeInTheDocument();
    expect(screen.queryByText('Pick #1')).not.toBeInTheDocument();
  });

  it('no source answered the guide search: the bar says so, not "nothing in the catalog", and the fold does not repeat it', async () => {
    mockApi({
      'POST /guide/stream': () =>
        sse(
          makeGuide({
            status: 'NO_CANDIDATES',
            model: null,
            aiContent: null,
            recommendations: [],
            candidatesConsidered: 0,
            sources: [makeStatus({ outcome: 'FAILED', resultCount: 0 })],
            notices: [{ code: 'ALL_SOURCES_FAILED', message: 'all failed' }],
          }),
        ),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');
    const bar = await screen.findByRole('region', { name: 'Guide picks' });
    expect(
      await within(bar).findByText("No source answered the guide's search. Try again in a moment."),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Nothing in the catalog matched/)).not.toBeInTheDocument();
    const fold = screen.getByText('How the guide decided').closest('details')!;
    expect(fold).not.toHaveAttribute('open');
    expect(within(fold).queryByText('No source could be reached.')).not.toBeInTheDocument();
  });

  it('the fold lists the guide search notices under "found", outside the AI-marked text, without the query expansion', async () => {
    mockApi({
      'POST /guide/stream': () =>
        sse(
          makeGuide({
            notices: [
              { code: 'QUERY_EXPANDED', message: 'expanded' },
              { code: 'CACHE_UNAVAILABLE', message: 'cache down' },
              { code: 'INDEX_UNAVAILABLE', message: 'index down' },
            ],
          }),
        ),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');
    await answered();
    const fold = screen.getByText('How the guide decided').closest('details')!;
    await userEvent.click(within(fold).getByText('How the guide decided'));
    const found = within(fold).getByText('2 · Found').closest('li')!;
    const cache = within(found).getByText(/Cache unavailable: every source was queried live/);
    expect(cache).toHaveTextContent('Notice:');
    expect(within(found).getByText(/Helper2 index unavailable/)).toBeInTheDocument();
    expect(within(fold).queryByText('expanded')).not.toBeInTheDocument();
    for (const n of found.querySelectorAll('.notice'))
      expect(n.closest('[data-ai-generated]')).toBeNull();
  });

  it('a name not in our sources: the bar builds the sentence, and only the name and the search are AI-marked and labelled', async () => {
    mockApi({
      'POST /guide/stream': () =>
        sse(
          makeGuide({
            status: 'PARTIAL',
            recommendations: [],
            intent: 'ALTERNATIVES',
            subjects: ['Frobnicator'],
            notices: [
              {
                code: 'GUIDE_SUBJECT_NOT_FOUND',
                message: 'The name in the question was not found in our sources.',
                subject: 'Frobnicator',
                query: 'local llm runner',
              },
            ],
          }),
        ),
    });
    const { container } = renderApp('/');
    await ask('an alternative to Frobnicator for local models');
    const bar = await screen.findByRole('region', { name: 'Guide picks' });
    const line = await within(bar).findByRole('status');
    await within(line).findByText('Frobnicator');
    expect(line).toHaveTextContent(
      'Frobnicator was not found in our sources; showing tools for: local llm runner Name and search AI-written',
    );
    const marked = [...line.querySelectorAll('[data-ai-generated="true"]')];
    expect(marked.map((m) => m.textContent)).toEqual(['Frobnicator', 'local llm runner']);
    for (const m of marked) expect(m).toHaveAttribute('data-ai-model', 'nvidia/nemotron-nano');
    expect(line.closest('[data-ai-generated]')).toBeNull();
    expect(
      within(line)
        .getByText(/was not found in our sources; showing tools for:/)
        .closest('[data-ai-generated]'),
    ).toBeNull();
    expect(within(line).queryByText(/The name in the question/)).not.toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('a name not in our sources next to picks: the fold says it with the same marking', async () => {
    mockApi({
      'POST /guide/stream': () =>
        sse(
          makeGuide({
            notices: [
              {
                code: 'GUIDE_SUBJECT_NOT_FOUND',
                message: 'A name to compare was not found in our sources.',
                subject: 'LM Studio',
                query: null,
              },
            ],
          }),
        ),
    });
    renderApp('/');
    await ask('Postgres MCP vs LM Studio');
    await answered();
    const fold = screen.getByText('How the guide decided').closest('details')!;
    await userEvent.click(within(fold).getByText('How the guide decided'));
    const notice = within(fold)
      .getByText(/was not found in our sources\./)
      .closest('p')!;
    expect(notice).toHaveTextContent('LM Studio was not found in our sources. Name AI-written');
    expect([...notice.querySelectorAll('[data-ai-generated]')].map((m) => m.textContent)).toEqual([
      'LM Studio',
    ]);
    expect(within(fold).queryByText(/A name to compare/)).not.toBeInTheDocument();
  });

  it("an older cached answer without the fields: the bar says it without the planner's words and without the AI label", async () => {
    mockApi({
      'POST /guide/stream': () =>
        sse(
          makeGuide({
            status: 'PARTIAL',
            recommendations: [],
            notices: [
              {
                code: 'GUIDE_SUBJECT_NOT_FOUND',
                message:
                  'Frobnicator was not found in our sources; showing tools for: local llm runner',
              },
            ],
          }),
        ),
    });
    renderApp('/');
    await ask('an alternative to Frobnicator for local models');
    const bar = await screen.findByRole('region', { name: 'Guide picks' });
    expect(
      await within(bar).findByText(
        'A name in the question was not found in our sources; showing the closest results.',
      ),
    ).toBeInTheDocument();
    expect(within(bar).queryByText(/Frobnicator/)).not.toBeInTheDocument();
    expect(within(bar).queryByText(/AI-written$/)).not.toBeInTheDocument();
  });

  it('model chose nothing: the bar says nothing fits and no row is a pick or carries a quote', async () => {
    const base = makeGuide();
    const fallback = {
      ...base.recommendations[0]!,
      reason: 'Top search result (no AI reasoning available).',
      aiReasoned: false,
    };
    mockApi({
      'POST /guide/stream': () =>
        sse(makeGuide({ status: 'PARTIAL', rejected: 0, recommendations: [fallback] })),
    });
    renderApp('/');
    await ask('a quantum blockchain toaster that writes poems');
    const bar = await screen.findByRole('region', { name: 'Guide picks' });
    expect(await within(bar).findByText('Nothing clearly fits your need.')).toBeInTheDocument();
    expect(screen.queryByText('Guide pick #1')).not.toBeInTheDocument();
    expect(screen.queryByText(/Some AI suggestions were removed/)).not.toBeInTheDocument();
    expect((await pickedRow()).queryByText(/checked word for word/i)).not.toBeInTheDocument();
    expect(document.querySelector('.h2-list mark')).toBeNull();
    expect(
      screen.queryByText('Top search result (no AI reasoning available).'),
    ).not.toBeInTheDocument();
  });

  it('rejected > 0 shows how many AI suggestions were removed', async () => {
    mockApi({ 'POST /guide/stream': () => sse(makeGuide({ status: 'PARTIAL', rejected: 2 })) });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(
      await screen.findByText(/2 AI suggestions removed: no supporting evidence/),
    ).toBeInTheDocument();
  });

  it('API error shows an alert and keeps the typed request', async () => {
    mockApi({
      'POST /guide/stream': () => problem(429, 'Too many requests', 'Please wait a minute.'),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await screen.findByRole('alert')).toHaveTextContent('Please wait a minute.');
    expect(screen.getByLabelText(BOX)).toHaveValue('an MCP server for Postgres');
  });

  it('a question in the URL is pre-filled and asked once', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp('/?q=MCP+server+for+Slack&ask=MCP+server+for+Slack');
    expect(await answered()).toBeInTheDocument();
    expect(screen.getByLabelText(BOX)).toHaveValue('MCP server for Slack');
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1);
    expect(m.callsTo('POST', '/guide/stream')[0]?.body).toEqual({
      request: 'MCP server for Slack',
    });
  });

  it('/guide is not a page: it shows the not-found page and asks no guide', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp('/guide?q=MCP+server+for+Slack');
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(0);
  });

  it('while the guide answers the picks bar says it is reading, and the line goes once the answer is in', async () => {
    let release!: (r: Response) => void;
    mockApi({
      'POST /guide/stream': () =>
        new Promise((r) => {
          release = r;
        }),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await screen.findByText(/Guide is reading the results/)).toBeInTheDocument();
    release(sse(makeGuide()));
    expect(await answered()).toBeInTheDocument();
    expect(screen.queryByText(/Guide is reading/)).not.toBeInTheDocument();
  });

  it('a failed guide request clears the spinner and offers a retry that asks again', async () => {
    let n = 0;
    const m = mockApi({
      'POST /guide/stream': () =>
        n++ === 0 ? problem(502, 'Bad gateway', 'The model timed out.') : sse(makeGuide()),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await screen.findByRole('alert')).toHaveTextContent('The model timed out.');
    expect(screen.queryByText(/Guide is reading/)).not.toBeInTheDocument();

    await userEvent.click(screen.getAllByRole('button', { name: /Try again/ })[0] as HTMLElement);
    expect(await answered()).toBeInTheDocument();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('a new question while one is answered replaces it: the old stream is aborted', async () => {
    const m = mockApi({
      'POST /guide/stream': ({ body }) =>
        (body as { request: string }).request.includes('Slack')
          ? sse(makeGuide())
          : new Promise(() => undefined),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');
    await screen.findByText(/Guide is reading/);
    await userEvent.clear(screen.getByLabelText(BOX));
    await ask('an MCP server for Slack');
    expect(await answered()).toBeInTheDocument();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(2);
    const firstSignal = (
      m.fetchMock.mock.calls.find(([, init]) =>
        String(init?.body).includes('Postgres'),
      )?.[1] as RequestInit
    ).signal;
    expect(firstSignal?.aborted).toBe(true);
  });

  it('a name search does not ask the guide: no picks bar, no model call', async () => {
    const m = mockApi();
    renderApp('/search?q=pgvector');
    await screen.findByRole('list', { name: 'Search results' });
    expect(screen.queryByRole('region', { name: 'Guide picks' })).not.toBeInTheDocument();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(0);
  });

  it('highlights the checked quote inside the resource’s own description, in the row of pick #1', async () => {
    const description =
      'Fast. An MCP server that gives agents  READ-ONLY access to a PostgreSQL database, with audit logs.';
    mockApi({
      'GET /search': () =>
        makeSearch({ results: [makeCard(), { ...PICKED, description }], total: 2 }),
      'POST /guide/stream': () => sse(makeGuide()),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');

    await answered();
    const mark = document.querySelector('.h2-list mark');
    expect(mark).toHaveTextContent(
      'MCP server that gives agents READ-ONLY access to a PostgreSQL database',
    );
    expect(mark?.parentElement).toHaveTextContent(/^Fast\. An .* with audit logs\.$/);
  });

  it('an error event from the stream shows its message', async () => {
    mockApi({
      'POST /guide/stream': () =>
        new Response(
          'event:error\ndata:{"title":"Guide failed","status":500,"detail":"The guide could not answer. Try again in a moment."}\n\n',
          { headers: { 'content-type': 'text/event-stream' } },
        ),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await screen.findByRole('alert')).toHaveTextContent('Try again in a moment.');
  });

  it('a stream that closes before the answer says so', async () => {
    mockApi({
      'POST /guide/stream': () =>
        new Response('event:planned\ndata:{"searchQuery":"mcp","constraints":[],"model":"m"}\n\n', {
          headers: { 'content-type': 'text/event-stream' },
        }),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The connection closed before the answer arrived.',
    );
  });
});

/** The user chooses the guide (ADR 0017, ADR 0025, UJ-2): Enter always searches; Shift+Enter or the button asks. */
describe('the guide runs when the user asks for it', () => {
  const results = async () => within(await screen.findByRole('list', { name: 'Search results' }));

  it('given 2 words, when the user presses Enter, then it only searches: no question, no model call', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    const { router, params } = renderApp('/');
    await userEvent.type(await screen.findByLabelText(BOX), 'postgres mcp{Enter}');
    await (await results()).findByText('Postgres MCP');
    expect(router.state.location.pathname).toBe('/search');
    expect(params().get('q')).toBe('postgres mcp');
    expect(params().get('ask')).toBeNull();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(0);
  });

  it('given a question of 6 words, when the user presses Enter, then it only searches: no /guide/stream call', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    const { params } = renderApp('/');
    await userEvent.type(await screen.findByLabelText(BOX), 'an MCP server for my Postgres{Enter}');
    await (await results()).findByText('Postgres MCP');
    expect(params().get('q')).toBe('an MCP server for my Postgres');
    expect(params().get('ask')).toBeNull();
    expect(screen.queryByRole('region', { name: 'Guide picks' })).not.toBeInTheDocument();
    expect(m.callsTo('GET', '/search')).toHaveLength(1);
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(0);
  });

  it('given 8 words, when the user clicks Search, then it only searches: no question, no model call', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    const { params } = renderApp('/');
    await userEvent.type(
      await screen.findByLabelText(BOX),
      'an MCP server for my Postgres database please',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await (await results()).findByText('Postgres MCP');
    expect(params().get('ask')).toBeNull();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(0);
  });

  it('given 1 word, when the user clicks Ask the guide, then it searches and the guide answers with its picks', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    const { params } = renderApp('/');
    await ask('pgvector');
    expect(await answered()).toBeInTheDocument();
    expect(params().get('q')).toBe('pgvector');
    expect(params().get('ask')).toBe('pgvector');
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1);
  });

  it('given text in the box, then the page with both buttons has no axe violations', async () => {
    mockApi();
    const { container } = renderApp('/');
    await userEvent.type(await screen.findByLabelText(BOX), 'an MCP server for Postgres');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('given 2 words, when the user presses Shift+Enter, then it searches and the guide answers with its picks', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    const { params } = renderApp('/');
    await userEvent.type(await screen.findByLabelText(BOX), 'postgres mcp{Shift>}{Enter}{/Shift}');
    expect(await answered()).toBeInTheDocument();
    expect(params().get('q')).toBe('postgres mcp');
    expect(params().get('ask')).toBe('postgres mcp');
    expect(m.callsTo('GET', '/search').length).toBeGreaterThanOrEqual(1);
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1);
  });

  it('given a question, when the user clicks Ask the guide, then it searches and the guide answers with its picks', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    const { params } = renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await answered()).toBeInTheDocument();
    expect(params().get('q')).toBe('an MCP server for Postgres');
    expect(params().get('ask')).toBe('an MCP server for Postgres');
    expect(m.callsTo('GET', '/search').length).toBeGreaterThanOrEqual(1);
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1);
  });
});
