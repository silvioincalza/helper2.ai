import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GuideResponse } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { mockApi, problem, sse } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import { makeCard, makeGuide, makeSearch, makeStatus } from '@/test/fixtures';

const PG = '22222222-2222-5222-8222-222222222222';
const OTHER = '11111111-1111-5111-8111-111111111111';
const BOX = 'Search AI resources';
const QUESTION = '/search?q=an+MCP+server+for+Postgres&ask=an+MCP+server+for+Postgres';

/** The search lists Acme Translator first and the guide's pick, Postgres MCP, second. */
const withPick = () => makeSearch({
  results: [makeCard(), makeCard({ id: PG, title: 'Postgres MCP', type: 'MCP_SERVER', description: 'Fast. An MCP server that gives agents  READ-ONLY access to a PostgreSQL database, with audit logs.' })],
  total: 2,
});
const bar = () => screen.getByRole('region', { name: /Guide picks/ });
const row = (title: string) => screen.getByRole('button', { name: new RegExp(`^${title} `) });
const preview = (title: string) => screen.getByRole('region', { name: title });

/** A guide stream the test feeds event by event. */
function stream() {
  const enc = new TextEncoder();
  let push!: (text: string) => void;
  let end!: () => void;
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      push = (text) => c.enqueue(enc.encode(text));
      end = () => c.close();
    },
  });
  return { response: () => new Response(body, { headers: { 'content-type': 'text/event-stream' } }), push, end };
}

describe('the guide picks bar', () => {
  it('given a question, the bar is there from the first render saying how many candidates the guide reads, then the picks as chips', async () => {
    const s = stream();
    mockApi({ 'GET /search': withPick, 'POST /guide/stream': s.response });
    renderApp(QUESTION);
    expect(within(bar()).getByRole('status')).toHaveTextContent('Guide is reading the results… picks will appear here');
    expect(within(bar()).getByText('an MCP server for Postgres')).toBeInTheDocument();

    s.push(`event:retrieved\ndata:${JSON.stringify({ candidates: 12, sources: [makeStatus()] })}\n\n`);
    expect(await within(bar()).findByText('Guide is reading 12 candidates… picks will appear here')).toBeInTheDocument();

    s.push(`event:answer\ndata:${JSON.stringify(makeGuide())}\n\n`);
    s.end();
    const chips = await within(bar()).findByRole('list', { name: "The guide's picks, best first" });
    expect(within(chips).getByRole('button', { name: '#1 Postgres MCP' })).toBeInTheDocument();
    expect(within(bar()).getByText('AI-written')).toBeInTheDocument();
    expect(within(bar()).queryByRole('status')).not.toBeInTheDocument();
  });

  it('the list never re-orders: the pick keeps its row and gets a "Pick #1" badge and its reason, marked AI-written', async () => {
    mockApi({ 'GET /search': withPick, 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp(QUESTION);
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    const rows = within(screen.getByRole('list', { name: 'Results' })).getAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('Acme Translator');
    expect(rows[1]).toHaveTextContent('Pick #1');
    const reason = within(rows[1] as HTMLElement).getByText('Lets an agent run read-only SQL against Postgres.');
    expect(reason).toHaveAttribute('data-ai-generated', 'true');
    expect(reason).toHaveAttribute('data-ai-model', 'nvidia/nemotron-3-super');
    expect(within(rows[0] as HTMLElement).queryByText(/Pick #/)).not.toBeInTheDocument();
  });

  it('tapping a chip selects that row, moves focus to it, and the preview shows the checked quote in the source text', async () => {
    mockApi({ 'GET /search': withPick, 'POST /guide/stream': () => sse(makeGuide()) });
    const { params, container } = renderApp(QUESTION);
    await userEvent.click(await screen.findByRole('button', { name: '#1 Postgres MCP' }));
    expect(params().get('id')).toBe(PG);
    expect(row('Postgres MCP')).toHaveFocus();
    expect(row('Postgres MCP')).toHaveAttribute('aria-current', 'true');
    const panel = preview('Postgres MCP');
    expect(within(panel).getByRole('heading', { level: 3, name: 'Why it matches' })).toBeInTheDocument();
    expect(within(panel).getByText('Quote checked')).toBeInTheDocument();
    const mark = panel.querySelector('mark');
    expect(mark).toHaveTextContent('MCP server that gives agents READ-ONLY access to a PostgreSQL database');
    expect(mark?.closest('blockquote')).toHaveTextContent(/^Fast\. An .* with audit logs\.$/);
    expect(mark?.closest('[data-ai-generated]')).toBeNull();
    // The model's explanation is labelled and shown at once: for "Guide me" on a tool it is the answer.
    const explanation = within(panel).getByText('Lets an agent run read-only SQL against Postgres.');
    expect(explanation.closest('details')).toHaveAttribute('open');
    expect(explanation).toHaveAttribute('data-ai-model', 'nvidia/nemotron-3-super');
    expect(within(panel).getByText(/Model's explanation \(not checked\)/)).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('a pick that is not among the results on the page links to its resource page', async () => {
    mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp(QUESTION);
    expect(await screen.findByRole('link', { name: '#1 Postgres MCP' })).toHaveAttribute('href', `/resource/${PG}`);
  });

  it('marks the answer machine-readable on the bar: provider and models; a chip holds a source title, so it is not marked (EU AI Act Art. 50(2), ADR 0038)', async () => {
    mockApi({ 'GET /search': withPick, 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp(QUESTION);
    const chip = await screen.findByRole('button', { name: '#1 Postgres MCP' });
    expect(chip.closest('[data-ai-generated]')).toBeNull();
    expect(bar()).toHaveAttribute('data-ai-provider', 'nebius-token-factory');
    expect(bar()).toHaveAttribute('data-ai-models', 'nvidia/nemotron-nano nvidia/nemotron-3-super');
  });

  it('shows each requirement in the preview as confirmed with its quote or as not confirmed, in words', async () => {
    const base = makeGuide();
    mockApi({
      'GET /search': withPick,
      'POST /guide/stream': () => sse(makeGuide({
        constraints: ['open source', 'free'],
        recommendations: [{ ...base.recommendations[0]!, checks: [
          { constraint: 'open source', status: 'CONFIRMED', quote: 'MIT licensed, open source' },
          { constraint: 'free', status: 'NOT_CONFIRMED', quote: null },
        ] }],
      })),
    });
    renderApp(`${QUESTION}&id=${PG}`);
    const checks = await screen.findByRole('list', { name: 'Your requirements, checked against the source' });
    const items = within(checks).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('open source: confirmed by the source: MIT licensed, open source');
    expect(items[1]).toHaveTextContent('free: not confirmed by the source');
  });

  it('with no pick, the bar shrinks to one line that says why', async () => {
    const fallback = { ...makeGuide().recommendations[0]!, aiReasoned: false };
    mockApi({ 'GET /search': withPick, 'POST /guide/stream': () => sse(makeGuide({ status: 'PARTIAL', recommendations: [fallback] })) });
    renderApp(QUESTION);
    await waitFor(() => expect(within(bar()).getByRole('status')).toHaveTextContent('No pick: no result had a sentence that proves it'));
    expect(bar()).toHaveClass('is-none');
    expect(screen.queryByText(/Pick #/)).not.toBeInTheDocument();
    expect(screen.queryByText('AI-written')).not.toBeInTheDocument();
  });

  it('when the model is not available, says so in the bar and marks nothing as AI-written', async () => {
    const fallback = { ...makeGuide().recommendations[0]!, aiReasoned: false };
    mockApi({ 'GET /search': withPick, 'POST /guide/stream': () => sse(makeGuide({ status: 'LLM_UNAVAILABLE', model: null, aiContent: null, recommendations: [fallback] })) });
    renderApp(QUESTION);
    await waitFor(() => expect(within(bar()).getByRole('status')).toHaveTextContent(/The AI model is not available right now/));
    expect(document.querySelector('[data-ai-generated]')).toBeNull();
  });

  it("over the daily quota, says the AI answers for today are used, not that the model is down", async () => {
    mockApi({ 'GET /search': withPick, 'POST /guide/stream': () => sse(makeGuide({ status: 'LLM_UNAVAILABLE', model: null, recommendations: [], notices: [{ code: 'GUIDE_DAILY_QUOTA', message: 'quota used' }] })) });
    renderApp(QUESTION);
    await waitFor(() => expect(within(bar()).getByRole('status')).toHaveTextContent(/You have used today's AI answers/));
  });

  it('says how many web searches the guide has left today, next to the ones of search', async () => {
    mockApi({ 'GET /search': () => makeSearch({ webSearches: { limit: 3, remaining: 2 } }), 'POST /guide/stream': () => sse(makeGuide({ webSearches: { limit: 3, remaining: 0 } })) });
    renderApp(QUESTION);
    expect(await screen.findByText(/Web searches today: 2 of 3 left for search · daily limit reached for the guide/)).toBeInTheDocument();
  });

  it('folds the rest of the answer under the bar: search terms, the evidence check, the models, and the prompt', async () => {
    mockApi({
      'GET /search': withPick,
      'POST /guide/stream': () => sse(makeGuide({
        status: 'PARTIAL', rejected: 2,
        prompt: { text: 'Summarise [notes] into decisions and owners.', basedOn: [{ resourceId: '33333333-3333-5333-8333-333333333333', title: 'Meeting summary prompt' }] },
      })),
    });
    renderApp(QUESTION);
    const summary = await screen.findByText('How the guide answered: Ready-to-use prompt');
    const details = summary.closest('details') as HTMLElement;
    expect(details).not.toHaveAttribute('open');
    await userEvent.click(summary);
    expect(details).toHaveAttribute('open');
    expect(within(details).getByText(/2 AI suggestions removed: no supporting evidence/)).toBeInTheDocument();
    expect(within(details).getByText('Planning: nvidia/nemotron-nano · Choosing: nvidia/nemotron-3-super · on nebius-token-factory')).toBeInTheDocument();
    expect(within(details).getByText('Summarise [notes] into decisions and owners.')).toHaveAttribute('data-ai-generated', 'true');
    expect(within(details).getByRole('link', { name: 'Meeting summary prompt' })).toHaveAttribute('href', '/resource/33333333-3333-5333-8333-333333333333');
    expect(within(details).getByText(/Third parties wrote the prompts it adapts/)).toBeInTheDocument();
  });

  it('a failed request says what went wrong in the bar, and Try again asks again', async () => {
    let n = 0;
    const m = mockApi({ 'GET /search': withPick, 'POST /guide/stream': () => (n++ === 0 ? problem(502, 'Bad gateway', 'The model timed out.') : sse(makeGuide())) });
    renderApp(QUESTION);
    expect(await within(bar()).findByRole('alert')).toHaveTextContent('The model timed out.');
    await userEvent.click(within(bar()).getByRole('button', { name: /Try again/ }));
    expect(await screen.findByRole('button', { name: '#1 Postgres MCP' })).toBeInTheDocument();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(2);
  });

  it('a stream that closes before the answer says so', async () => {
    mockApi({
      'POST /guide/stream': () => new Response('event:planned\ndata:{"searchQuery":"mcp","constraints":[],"model":"m"}\n\n',
        { headers: { 'content-type': 'text/event-stream' } }),
    });
    renderApp(QUESTION);
    expect(await screen.findByRole('alert')).toHaveTextContent('The connection closed before the answer arrived.');
  });

  it('closing the bar aborts the stream, keeps the search and moves focus to the results heading', async () => {
    const m = mockApi({ 'GET /search': withPick, 'POST /guide/stream': () => new Promise(() => undefined) });
    const { params } = renderApp(QUESTION);
    await waitFor(() => expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1));
    await userEvent.click(screen.getByRole('button', { name: 'Close the guide answer' }));
    expect(screen.queryByRole('region', { name: /Guide picks/ })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: /results?$/ })).toHaveFocus();
    const init = m.fetchMock.mock.calls.find(([input]) => String(input).endsWith('/guide/stream'))?.[1];
    expect(init?.signal?.aborted).toBe(true);
    expect(params().get('ask')).toBeNull();
    expect(params().get('q')).toBe('an MCP server for Postgres');
    expect(await screen.findByRole('button', { name: /^Postgres MCP / })).toBeInTheDocument();
  });

  it('a new question while one is answered replaces it: the old stream is aborted', async () => {
    const m = mockApi({ 'POST /guide/stream': ({ body }) => ((body as { request: string }).request.includes('Slack') ? sse(makeGuide()) : new Promise(() => undefined)) });
    renderApp(QUESTION);
    await waitFor(() => expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1));
    await userEvent.clear(screen.getByLabelText(BOX));
    await userEvent.type(screen.getByLabelText(BOX), 'an MCP server for Slack');
    await userEvent.click(screen.getByRole('button', { name: 'Ask the guide' }));
    expect(await screen.findByRole('link', { name: '#1 Postgres MCP' })).toBeInTheDocument();
    expect(within(bar()).getByText('an MCP server for Slack')).toBeInTheDocument();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(2);
    const first = (m.fetchMock.mock.calls.find(([, init]) => String(init?.body).includes('Postgres'))?.[1] as RequestInit).signal;
    expect(first?.aborted).toBe(true);
  });

  it('a question in the URL is put in the box and asked once', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp('/search?q=MCP+server+for+Slack&ask=MCP+server+for+Slack');
    expect(await screen.findByRole('link', { name: '#1 Postgres MCP' })).toBeInTheDocument();
    expect(screen.getByLabelText(BOX)).toHaveValue('MCP server for Slack');
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1);
    expect(m.callsTo('POST', '/guide/stream')[0]?.body).toEqual({ request: 'MCP server for Slack' });
  });

  it('a long search without a question offers to ask the guide', async () => {
    const m = mockApi({ 'POST /guide/stream': () => new Promise(() => undefined) });
    const { params } = renderApp('/search?q=summarise+my+meeting+notes+quickly');
    await userEvent.click(await screen.findByRole('button', { name: /ask the guide: “summarise my meeting notes quickly”/ }));
    await waitFor(() => expect(params().get('ask')).toBe('summarise my meeting notes quickly'));
    await waitFor(() => expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1));
  });
});

const BASE: GuideResponse = { ...makeGuide(), recommendations: [], model: null, aiContent: null, generatedAt: '2026-10-28T18:00:00Z', constraints: [] };
const PLANNER: GuideResponse['aiContent'] = { aiGenerated: true, provider: 'nebius-token-factory', models: ['nvidia/nemotron-nano'], fields: [], policy: '/mission' };

describe('the guide intents in the bar (ADR 0032)', () => {
  it('asks the question back with options, free text and search anyway, marked as the planner wrote them', async () => {
    const m = mockApi({ 'POST /guide/stream': ({ body }) => sse((body as { clarification?: string }).clarification === undefined
      ? { ...BASE, status: 'NEEDS_CLARIFICATION', aiContent: PLANNER, clarification: { question: 'New tools for what?', options: ['coding', 'agents'] } }
      : BASE) });
    renderApp('/search?q=new+tools&ask=new+tools');
    const question = await screen.findByRole('heading', { name: 'New tools for what?' });
    expect(question).toHaveAttribute('data-ai-model', 'nvidia/nemotron-nano');
    expect(bar()).toHaveAttribute('data-ai-provider', 'nebius-token-factory');
    expect(within(bar()).getByText('AI-written')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'coding' })).toHaveAttribute('data-ai-generated', 'true');
    expect(screen.getByRole('button', { name: 'Search anyway' })).not.toHaveAttribute('data-ai-generated');
    await userEvent.type(screen.getByLabelText('Your answer'), 'speech to text');
    await userEvent.click(screen.getByRole('button', { name: 'Ask' }));
    await waitFor(() => expect(m.callsTo('POST', '/guide/stream')[1]?.body).toEqual({ request: 'new tools', clarification: 'speech to text' }));
  });

  it('shows how the question was read while the guide still works, from the stream', async () => {
    const s = stream();
    const m = mockApi({ 'POST /guide/stream': s.response });
    renderApp('/search?q=new+mcp+servers&ask=new+mcp+servers');
    await waitFor(() => expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1));
    s.push('event:planned\ndata:{"searchQuery":"mcp servers","constraints":["free"],"model":"m"}\n\n');
    s.push('event:understood\ndata:{"intent":"NEWEST","subjects":[],"window":null}\n\n');
    expect(await within(bar()).findByText('New resources')).toBeInTheDocument();
    expect(within(bar()).getByText('free')).toHaveAttribute('data-ai-generated', 'true');
    // Nothing to remove while the guide works: the answer would replace the one it is writing.
    expect(within(bar()).queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument();
    await act(async () => s.end());
  });

  it('removes a part of the reading as a structured drop; with a period only the period can go', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse({ ...BASE, intent: 'NEWEST', window: { from: '2026-09-28', to: '2026-10-28' }, constraints: ['free'] }) });
    const { params } = renderApp('/search?q=new+tools&ask=new+tools');
    expect(await within(bar()).findByText('New resources')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove “New resources” and ask again' })).not.toBeInTheDocument();
    expect(screen.getByText('Created 2026-09-28 – 2026-10-28').closest('[data-ai-generated]')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Remove “Created 2026-09-28 – 2026-10-28” and ask again' }));
    await waitFor(() => expect(params().getAll('drop')).toEqual(['window']));
    await waitFor(() => expect(m.callsTo('POST', '/guide/stream')[1]?.body).toEqual({ request: 'new tools', drop: ['window'] }));
  });

  it('removes the intent when no period holds it in place', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse({ ...BASE, intent: 'NEWEST' }) });
    renderApp('/search?q=new+tools&ask=new+tools');
    await userEvent.click(await screen.findByRole('button', { name: 'Remove “New resources” and ask again' }));
    await waitFor(() => expect(m.callsTo('POST', '/guide/stream')[1]?.body).toEqual({ request: 'new tools', drop: ['intent'] }));
  });

  it('a comparison: the bar says where it is; it compares side by side and says when a source does not state something', async () => {
    mockApi({
      'POST /guide/stream': () => sse({
        ...BASE, intent: 'COMPARE', subjects: ['Ollama', 'LM Studio'],
        comparison: {
          subjects: [
            { key: 's1', name: 'Ollama', resourceId: OTHER, title: 'ollama', url: 'https://github.com/ollama/ollama' },
            { key: 's2', name: 'LM Studio', resourceId: null, title: null, url: null },
          ],
          rows: [{ aspect: 'Licence', cells: [{ subjectKey: 's1', text: 'MIT', quote: 'MIT License', reasonCheck: 'SUPPORTED' }, { subjectKey: 's2', text: null, quote: null, reasonCheck: 'NOT_CHECKED' }] }],
        },
      }),
    });
    renderApp('/search?q=ollama+vs+lm+studio&ask=ollama+vs+lm+studio');
    await waitFor(() => expect(within(bar()).getByRole('status')).toHaveTextContent('No picks: the guide compared them.'));
    expect(screen.getByText('Ollama', { selector: '.understood-chip' }).closest('[data-ai-generated]')).toBeNull();
    await userEvent.click(screen.getByText('How the guide answered: Side by side'));
    expect(screen.getByRole('columnheader', { name: 'ollama' })).toBeInTheDocument();
    expect(screen.getByText(/Not found in our sources/)).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: /MIT/ })).toHaveTextContent('MIT License');
    expect(screen.getByText('Not stated by the source')).toBeInTheDocument();
    expect(screen.getByText('Licence')).toHaveAttribute('data-ai-generated', 'true');
  });

  it('an explanation: each sentence with the resource and the quote it rests on', async () => {
    mockApi({
      'POST /guide/stream': () => sse({
        ...BASE, intent: 'WHAT_IS',
        explanation: { sentences: [{
          text: 'Ollama runs language models locally.', quote: 'Get up and running with large language models locally.',
          resourceId: OTHER, title: 'ollama', url: 'https://github.com/ollama/ollama', reasonCheck: 'SUPPORTED',
        }] },
      }),
    });
    renderApp('/search?q=what+is+ollama&ask=what+is+ollama');
    await waitFor(() => expect(within(bar()).getByRole('status')).toHaveTextContent('No picks: the guide explained it.'));
    await userEvent.click(screen.getByText('How the guide answered: What it is'));
    expect(screen.getByRole('link', { name: 'ollama' })).toHaveAttribute('href', `/resource/${OTHER}`);
    expect(screen.getByText('Ollama runs language models locally.')).toHaveAttribute('data-ai-generated', 'true');
    expect(screen.getByText('Get up and running with large language models locally.')).toBeInTheDocument();
  });

  it('marks the planner-written search query when the answering model failed', async () => {
    mockApi({ 'POST /guide/stream': () => sse({ ...BASE, status: 'LLM_UNAVAILABLE', aiContent: PLANNER, searchQuery: 'speech recognition' }) });
    renderApp('/search?q=speech&ask=speech+to+text');
    expect(await screen.findByText(/speech recognition/)).toHaveAttribute('data-ai-model', 'nvidia/nemotron-nano');
  });

  it('says in the bar when something the question names is not in our sources', async () => {
    mockApi({ 'POST /guide/stream': () => sse({ ...BASE, notices: [{ code: 'GUIDE_SUBJECT_NOT_FOUND', message: 'Cursor was not found in our sources; showing tools for: code editor.' }] }) });
    renderApp('/search?q=alternatives+to+cursor&ask=alternatives+to+cursor');
    expect(await within(bar()).findByText(/Cursor was not found in our sources/)).toBeInTheDocument();
  });
});
