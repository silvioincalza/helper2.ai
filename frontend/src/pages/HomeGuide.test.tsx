import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GuideResponse } from '@/api/types';
import { mockApi, problem, sse } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import { makeStatus } from '@/test/fixtures';

function makeGuide(over: Partial<GuideResponse> = {}): GuideResponse {
  return {
    status: 'OK',
    searchQuery: 'mcp server postgres',
    recommendations: [{
      resourceId: '22222222-2222-5222-8222-222222222222',
      title: 'Postgres MCP',
      url: 'https://github.com/acme/postgres-mcp',
      type: 'MCP_SERVER',
      reason: 'Lets an agent run read-only SQL against Postgres.',
      quote: 'MCP server that gives agents read-only access to a PostgreSQL database',
      sourceIds: ['github'],
      aiReasoned: true,
      reasonCheck: 'NOT_CHECKED',
    }],
    prompt: null,
    model: { provider: 'nebius-token-factory', fastModel: 'nvidia/nemotron-nano', reasoningModel: 'nvidia/nemotron-3-super' },
    aiContent: {
      aiGenerated: true, provider: 'nebius-token-factory', models: ['nvidia/nemotron-nano', 'nvidia/nemotron-3-super'],
      fields: ['searchQuery', 'recommendations[].reason'], policy: '/mission',
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
  it('asks with the text in the box, also searches it, and renders reason, verified quote and resource link', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    const { params } = renderApp('/');
    await ask('an MCP server for Postgres');

    expect(await screen.findByRole('link', { name: 'Postgres MCP' })).toHaveAttribute('href', '/resource/22222222-2222-5222-8222-222222222222');
    expect(m.callsTo('POST', '/guide/stream')[0]?.body).toEqual({ request: 'an MCP server for Postgres' });
    expect(params().get('ask')).toBe('an MCP server for Postgres');
    expect(params().get('q')).toBe('an MCP server for Postgres');
    expect(screen.getByText(/Model's explanation \(not checked\)/)).toBeInTheDocument();
    // Shown at once: for "Guide me" on a tool, the explanation is the answer.
    expect(screen.getByText('Lets an agent run read-only SQL against Postgres.').closest('details')).toHaveAttribute('open');
    expect(screen.getByText(/Evidence, checked word for word/)).toBeInTheDocument();
    expect(screen.getByText(/read-only access to a PostgreSQL database/)).toBeInTheDocument();
  });

  it('marks AI-written text machine-readable, and never the quote from the source (EU AI Act Art. 50(2))', async () => {
    mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp('/');
    await ask('an MCP server for Postgres');

    const reason = await screen.findByText('Lets an agent run read-only SQL against Postgres.');
    expect(reason).toHaveAttribute('data-ai-generated', 'true');
    const answer = reason.closest('section.guide-answer');
    expect(answer).toHaveAttribute('data-ai-provider', 'nebius-token-factory');
    expect(answer).toHaveAttribute('data-ai-models', 'nvidia/nemotron-nano nvidia/nemotron-3-super');
    expect(screen.getByText(/read-only access to a PostgreSQL database/).closest('[data-ai-generated]')).toBeNull();
  });

  it('shows each requirement as confirmed with its quote or as not confirmed, in words', async () => {
    const base = makeGuide();
    mockApi({
      'POST /guide/stream': () => sse(makeGuide({
        constraints: ['open source', 'free'],
        recommendations: [{
          ...base.recommendations[0]!,
          checks: [
            { constraint: 'open source', status: 'CONFIRMED', quote: 'MIT licensed, open source' },
            { constraint: 'free', status: 'NOT_CONFIRMED', quote: null },
          ],
        }],
      })),
    });
    renderApp('/');
    await ask('a free open-source MCP server for Postgres');

    const checks = await screen.findByRole('list', { name: 'Your requirements, checked against the source' });
    const items = within(checks).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('open source: confirmed by the source: MIT licensed, open source');
    expect(items[1]).toHaveTextContent('free: not confirmed by the source');
  });

  it('shows no requirement list when the need stated none', async () => {
    mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp('/');
    await ask('an MCP server for Postgres');
    await screen.findByRole('link', { name: 'Postgres MCP' });
    expect(screen.queryByRole('list', { name: 'Your requirements, checked against the source' })).not.toBeInTheDocument();
  });

  it('shows which model planned and which model chose, and on which provider', async () => {
    mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await screen.findByText('Planning: nvidia/nemotron-nano · Choosing: nvidia/nemotron-3-super · on nebius-token-factory')).toBeInTheDocument();
  });

  it('shows an adapted prompt with its catalog sources and a warning to read it before pasting', async () => {
    mockApi({
      'POST /guide/stream': () => sse(makeGuide({
        prompt: {
          text: 'Summarise [notes] into decisions and owners.',
          basedOn: [{ resourceId: '33333333-3333-5333-8333-333333333333', title: 'Meeting summary prompt' }],
        },
      })),
    });
    renderApp('/');
    await ask('a prompt to summarise meeting notes');
    expect(await screen.findByText('Ready-to-use prompt')).toBeInTheDocument();
    expect(screen.getByText('Summarise [notes] into decisions and owners.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Meeting summary prompt' })).toHaveAttribute('href', '/resource/33333333-3333-5333-8333-333333333333');
    expect(screen.getByText(/Third parties wrote the prompts it adapts/)).toBeInTheDocument();
  });

  it('LLM_UNAVAILABLE shows the degraded notice and no-AI badges, without model explanation or prompt', async () => {
    const fallback = { ...makeGuide().recommendations[0]!, aiReasoned: false };
    mockApi({ 'POST /guide/stream': () => sse(makeGuide({ status: 'LLM_UNAVAILABLE', model: null, aiContent: null, prompt: null, recommendations: [fallback] })) });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await screen.findByText(/The AI model is not available right now/)).toBeInTheDocument();
    const card = screen.getByRole('link', { name: 'Postgres MCP' }).closest('li');
    expect(card).not.toBeNull();
    expect(within(card as HTMLElement).getByText('Top search result, no AI reasoning')).toBeInTheDocument();
    expect(screen.queryByText(/Model's explanation/)).not.toBeInTheDocument();
    expect(screen.queryByText('Ready-to-use prompt')).not.toBeInTheDocument();
    expect(screen.queryByText(/^Planning:/)).not.toBeInTheDocument();
    expect(document.querySelector('[data-ai-generated]')).toBeNull();
  });

  it('over the daily quota says so instead of calling the model unavailable', async () => {
    const fallback = { ...makeGuide().recommendations[0]!, aiReasoned: false };
    mockApi({
      'POST /guide/stream': () => sse(makeGuide({
        status: 'LLM_UNAVAILABLE', model: null, recommendations: [fallback],
        notices: [{ code: 'GUIDE_DAILY_QUOTA', message: 'quota used' }],
      })),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await screen.findByText(/You have used today's AI answers/)).toBeInTheDocument();
    expect(screen.queryByText(/The AI model is not available right now/)).not.toBeInTheDocument();
    expect(screen.getByText('Top search result, no AI reasoning')).toBeInTheDocument();
  });

  it('model chose nothing: fallback results are labelled as not AI and the notice says nothing fits', async () => {
    const base = makeGuide();
    const fallback = { ...base.recommendations[0]!, reason: 'Top search result (no AI reasoning available).', aiReasoned: false };
    mockApi({ 'POST /guide/stream': () => sse(makeGuide({ status: 'PARTIAL', rejected: 0, recommendations: [fallback] })) });
    renderApp('/');
    await ask('a quantum blockchain toaster that writes poems');
    expect(await screen.findByText(/The AI found nothing in the results that clearly fits/)).toBeInTheDocument();
    expect(screen.getByText('Top search result, no AI reasoning')).toBeInTheDocument();
    expect(screen.queryByText(/Model's explanation/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Some AI suggestions were removed/)).not.toBeInTheDocument();
    expect(screen.getByText(/From the resource's own description/)).toBeInTheDocument();
    expect(screen.queryByText(/checked word for word/)).not.toBeInTheDocument();
  });

  it('rejected > 0 shows how many AI suggestions were removed', async () => {
    mockApi({ 'POST /guide/stream': () => sse(makeGuide({ status: 'PARTIAL', rejected: 2 })) });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await screen.findByText(/2 AI suggestions removed: no supporting evidence/)).toBeInTheDocument();
  });

  it('API error shows an alert and keeps the typed request', async () => {
    mockApi({ 'POST /guide/stream': () => problem(429, 'Too many requests', 'Please wait a minute.') });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await screen.findByRole('alert')).toHaveTextContent('Please wait a minute.');
    expect(screen.getByLabelText(BOX)).toHaveValue('an MCP server for Postgres');
  });

  it('a question in the URL is pre-filled and asked once', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    renderApp('/?q=MCP+server+for+Slack&ask=MCP+server+for+Slack');
    expect(await screen.findByRole('link', { name: 'Postgres MCP' })).toBeInTheDocument();
    expect(screen.getByLabelText(BOX)).toHaveValue('MCP server for Slack');
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1);
    expect(m.callsTo('POST', '/guide/stream')[0]?.body).toEqual({ request: 'MCP server for Slack' });
  });

  it('old /guide?q= links ask the guide on the one page', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    const { router } = renderApp('/guide?q=MCP+server+for+Slack&path=BUILD_WITH_AI');
    expect(await screen.findByRole('link', { name: 'Postgres MCP' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
    expect(m.callsTo('POST', '/guide/stream')[0]?.body).toEqual({ request: 'MCP server for Slack' });
  });

  it('while the guide answers: spinner status and busy region, both cleared on success', async () => {
    let release!: (r: Response) => void;
    mockApi({ 'POST /guide/stream': () => new Promise((r) => { release = r; }) });
    renderApp('/');
    await ask('an MCP server for Postgres');

    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('Understanding your need');
    expect(status.querySelector('.spinner')).toHaveAttribute('aria-hidden', 'true');
    expect(document.querySelector('.guide-panel [aria-busy="true"]')).not.toBeNull();

    release(sse(makeGuide()));
    expect(await screen.findByRole('link', { name: 'Postgres MCP' })).toBeInTheDocument();
    expect(screen.queryByText('Understanding your need')).not.toBeInTheDocument();
    expect(document.querySelector('.guide-panel [aria-busy="true"]')).toBeNull();
  });

  it('a failed guide request clears the spinner and offers a retry that asks again', async () => {
    let n = 0;
    const m = mockApi({ 'POST /guide/stream': () => (n++ === 0 ? problem(502, 'Bad gateway', 'The model timed out.') : sse(makeGuide())) });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await screen.findByRole('alert')).toHaveTextContent('The model timed out.');
    expect(screen.queryByText('Understanding your need')).not.toBeInTheDocument();

    await userEvent.click(within(screen.getByRole('region', { name: /an MCP server for Postgres/ })).getByRole('button', { name: /Try again/ }));
    expect(await screen.findByRole('link', { name: 'Postgres MCP' })).toBeInTheDocument();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('a new question while one is answered replaces it: the old stream is aborted', async () => {
    const m = mockApi({ 'POST /guide/stream': ({ body }) => ((body as { request: string }).request.includes('Slack') ? sse(makeGuide()) : new Promise(() => undefined)) });
    renderApp('/');
    await ask('an MCP server for Postgres');
    await screen.findByRole('status');
    await userEvent.clear(screen.getByLabelText(BOX));
    await ask('an MCP server for Slack');
    expect(await screen.findByRole('link', { name: 'Postgres MCP' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /an MCP server for Slack/ })).toBeInTheDocument();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(2);
    const firstSignal = (m.fetchMock.mock.calls.find(([, init]) => String(init?.body).includes('Postgres'))?.[1] as RequestInit).signal;
    expect(firstSignal?.aborted).toBe(true);
  });

  it('closing the panel mid-request aborts the stream', async () => {
    const m = mockApi({ 'POST /guide/stream': () => new Promise(() => undefined) });
    renderApp('/');
    await ask('an MCP server for Postgres');
    await screen.findByRole('status');
    await userEvent.click(screen.getByRole('button', { name: 'Close the guide answer' }));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    const init = m.fetchMock.mock.calls.find(([input]) => String(input).endsWith('/guide/stream'))?.[1];
    expect(init?.signal?.aborted).toBe(true);
  });

  it('Shift+Enter asks the guide; closing the answer keeps the search', async () => {
    const m = mockApi({ 'POST /guide/stream': () => sse(makeGuide()) });
    const { params } = renderApp('/');
    await userEvent.type(await screen.findByLabelText(BOX), 'an MCP server for Postgres{Shift>}{Enter}{/Shift}');
    expect(await screen.findByRole('link', { name: 'Postgres MCP' })).toBeInTheDocument();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'Close the guide answer' }));
    expect(screen.queryByRole('link', { name: 'Postgres MCP' })).not.toBeInTheDocument();
    expect(params().get('ask')).toBeNull();
    expect(params().get('q')).toBe('an MCP server for Postgres');
  });


  it('shows each real step from the stream while the guide works, then the answer', async () => {
    const enc = new TextEncoder();
    let push!: (text: string) => void;
    let end!: () => void;
    const body = new ReadableStream<Uint8Array>({
      start(c) {
        push = (text) => c.enqueue(enc.encode(text));
        end = () => c.close();
      },
    });
    mockApi({ 'POST /guide/stream': () => new Response(body, { headers: { 'content-type': 'text/event-stream' } }) });
    renderApp('/');
    await ask('an MCP server for Postgres');

    push('event:planned\ndata:{"searchQuery":"mcp server postgres","constraints":[],"model":"nvidia/nemotron-nano"}\n\n');
    expect(await screen.findByText('Search terms: “mcp server postgres”')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Searching the sources');

    push(`event:retrieved\ndata:${JSON.stringify({ candidates: 12, sources: [makeStatus()] })}\n\n`);
    expect(await screen.findByText('12 real candidates · 1 of 1 sources answered')).toBeInTheDocument();
    push('event:chosen\ndata:{"proposed":3}\n\nevent:validated\ndata:{"kept":1,"rejected":2}\n\n');
    expect(await screen.findByText('1 kept · 2 rejected')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Checking each reason against its quote');
    push('event:checked\ndata:{"supported":1,"removed":0,"notChecked":0}\n\n');
    expect(await screen.findByText('Checked the reasons: 1 supported, 0 removed')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Preparing the answer');

    push(`event:answer\ndata:${JSON.stringify(makeGuide())}\n\n`);
    end();
    expect(await screen.findByRole('link', { name: 'Postgres MCP' })).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('highlights the checked quote inside the resource’s own description', async () => {
    const base = makeGuide();
    const description = 'Fast. An MCP server that gives agents  READ-ONLY access to a PostgreSQL database, with audit logs.';
    mockApi({ 'POST /guide/stream': () => sse(makeGuide({ recommendations: [{ ...base.recommendations[0]!, description }] })) });
    renderApp('/');
    await ask('an MCP server for Postgres');

    await screen.findByRole('link', { name: 'Postgres MCP' });
    const mark = document.querySelector('mark');
    expect(mark).toHaveTextContent('MCP server that gives agents READ-ONLY access to a PostgreSQL database');
    expect(mark?.parentElement).toHaveTextContent(/^Fast\. An .* with audit logs\.$/);
    expect(screen.getByText('Best match')).toBeInTheDocument();
  });

  it('an error event from the stream shows its message', async () => {
    mockApi({
      'POST /guide/stream': () => new Response(
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
      'POST /guide/stream': () => new Response('event:planned\ndata:{"searchQuery":"mcp","constraints":[],"model":"m"}\n\n',
        { headers: { 'content-type': 'text/event-stream' } }),
    });
    renderApp('/');
    await ask('an MCP server for Postgres');
    expect(await screen.findByRole('alert')).toHaveTextContent('The connection closed before the answer arrived.');
  });
});
