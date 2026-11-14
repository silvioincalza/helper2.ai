import { api, ApiError, csrfTokenFrom, deleteMyAccount, signOut } from './client';
import { json, mockApi, problem } from '@/test/mockApi';
import type { GuideStep } from './types';

describe('csrfTokenFrom', () => {
  it('reads the public site cookie', () => {
    expect(csrfTokenFrom('a=1; XSRF-TOKEN=abc%3D123')).toBe('abc=123');
  });
  it('reads the back office __Host- cookie, which wins over a planted plain one', () => {
    expect(csrfTokenFrom('__Host-XSRF-TOKEN=host')).toBe('host');
    expect(csrfTokenFrom('XSRF-TOKEN=planted; __Host-XSRF-TOKEN=host')).toBe('host');
  });
  it('is undefined without either cookie', () => {
    expect(csrfTokenFrom('SESSION=x; XSRF-TOKEN=')).toBeUndefined();
    expect(csrfTokenFrom('')).toBeUndefined();
  });
});

describe('api client', () => {
  it('sends the CSRF header and same-origin credentials on POST only', async () => {
    document.cookie = 'XSRF-TOKEN=abc%3D123';
    const m = mockApi({ 'POST /me/bookmarks': () => [] });
    await api.getMe();
    await api.mergeLocalBookmarks([]);
    expect(m.calls[0]?.headers.get('X-XSRF-TOKEN')).toBeNull();
    expect(m.calls[1]?.headers.get('X-XSRF-TOKEN')).toBe('abc=123');
    expect(m.fetchMock.mock.calls[1]?.[1]).toMatchObject({
      credentials: 'same-origin',
      method: 'POST',
    });
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });
  it('on a 403 without a token, loads the token from GET /me and sends the write once more', async () => {
    let posts = 0;
    const m = mockApi({
      'GET /me': () => {
        document.cookie = 'XSRF-TOKEN=fresh';
        return json({ authenticated: false });
      },
      'POST /me/bookmarks': () => {
        posts += 1;
        return posts === 1 ? problem(403, 'Forbidden') : json([]);
      },
    });
    await expect(api.mergeLocalBookmarks([])).resolves.toEqual([]);
    const sent = m.callsTo('POST', '/me/bookmarks');
    expect(sent).toHaveLength(2);
    expect(sent[0]?.headers.get('X-XSRF-TOKEN')).toBeNull();
    expect(sent[1]?.headers.get('X-XSRF-TOKEN')).toBe('fresh');
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });
  it('a write cancelled while the token loads ends as a cancel, not as a 403', async () => {
    const ctrl = new AbortController();
    mockApi({
      'GET /me': () => {
        ctrl.abort();
        throw new DOMException('Aborted', 'AbortError');
      },
      'POST /resources/x/guide': () => problem(403, 'Forbidden'),
    });
    const err = await api.askResourceGuide('x', ctrl.signal).catch((e: unknown) => e);
    expect(err).not.toBeInstanceOf(ApiError);
    expect((err as DOMException).name).toBe('AbortError');
  });
  it('does not retry a 403 when a token was sent', async () => {
    document.cookie = 'XSRF-TOKEN=tok';
    const m = mockApi({ 'POST /me/bookmarks': () => problem(403, 'Forbidden') });
    await expect(api.mergeLocalBookmarks([])).rejects.toMatchObject({ status: 403 });
    expect(m.callsTo('POST', '/me/bookmarks')).toHaveLength(1);
    expect(m.callsTo('GET', '/me')).toHaveLength(0);
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });
  it('sends Accept-Language from the document language', async () => {
    document.documentElement.lang = 'it';
    const m = mockApi();
    await api.getMe();
    expect(m.calls[0]?.headers.get('Accept-Language')).toBe('it');
  });
  it('parses problem+json into ApiError', async () => {
    mockApi({ 'GET /sources': () => problem(429, 'Too many requests', 'Slow down') });
    await expect(api.listSources()).rejects.toMatchObject({
      status: 429,
      problem: { detail: 'Slow down' },
    });
  });
  it('wraps network failures as status 0', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('x')));
    const err = await api.getMe().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(0);
  });
  it('handles 204 on delete', async () => {
    mockApi({ 'DELETE /me/bookmarks/*': () => new Response(null, { status: 204 }) });
    await expect(api.deleteBookmark('x')).resolves.toBeUndefined();
  });
  it('deleteMyAccount sends DELETE /me with the CSRF header, then reloads', async () => {
    document.cookie = 'XSRF-TOKEN=tok';
    const m = mockApi({ 'DELETE /me': () => new Response(null, { status: 204 }) });
    const reload = vi.fn();
    await deleteMyAccount(reload);
    expect(m.callsTo('DELETE', '/me')[0]?.headers.get('X-XSRF-TOKEN')).toBe('tok');
    expect(reload).toHaveBeenCalled();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });
  it('deleteMyAccount does not reload when the server refuses', async () => {
    mockApi({ 'DELETE /me': () => problem(401, 'Unauthorized') });
    const reload = vi.fn();
    await expect(deleteMyAccount(reload)).rejects.toMatchObject({ status: 401 });
    expect(reload).not.toHaveBeenCalled();
  });
  it('signOut POSTs /logout then reloads', async () => {
    document.cookie = 'XSRF-TOKEN=tok';
    const m = mockApi({ 'POST /logout': () => json({}) });
    const reload = vi.fn();
    await signOut(reload);
    expect(m.fetchMock.mock.calls[0]?.[0]).toMatch(/\/logout$/);
    expect(m.calls[0]?.headers.get('X-XSRF-TOKEN')).toBe('tok');
    expect(reload).toHaveBeenCalled();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });
});

function liveStream() {
  const enc = new TextEncoder();
  const state = { cancelled: false };
  let ctrl!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      ctrl = c;
    },
    cancel() {
      state.cancelled = true;
    },
  });
  return {
    state,
    push: (text: string) => ctrl.enqueue(enc.encode(text)),
    end: () => ctrl.close(),
    drop: () => ctrl.error(new TypeError('network error')),
    response: () => new Response(body, { headers: { 'content-type': 'text/event-stream' } }),
  };
}

const ANSWER = {
  status: 'OK',
  searchQuery: 'q',
  recommendations: [],
  model: null,
  candidatesConsidered: 0,
  rejected: 0,
  sources: [],
  notices: [],
  generatedAt: '2026-10-27T09:00:00Z',
};
const ask = (onStep: (s: GuideStep) => void = () => undefined, signal?: AbortSignal) =>
  api.askGuideStream({ request: 'an MCP server' }, onStep, signal);

describe('guide answer stream', () => {
  afterEach(() => vi.useRealTimers());

  it('reports each step, resolves with the answer and cancels the reader', async () => {
    const s = liveStream();
    mockApi({ 'POST /guide/stream': () => s.response() });
    const steps: GuideStep[] = [];
    const answer = ask((st) => steps.push(st));
    s.push('event:planned\ndata:{"searchQuery":"mcp","constraints":[],"model":"m"}\n\n');
    s.push('event:checked\ndata:{"supported":1,"removed":0,"notChecked":0}\n\n');
    s.push(`event:answer\ndata:${JSON.stringify(ANSWER)}\n\n`);
    await expect(answer).resolves.toMatchObject({ status: 'OK' });
    expect(steps.map((st) => st.kind)).toEqual(['planned', 'checked']);
    await vi.waitFor(() => expect(s.state.cancelled).toBe(true));
  });

  it('reports that a model call reasons and writes as steps with the step only', async () => {
    const s = liveStream();
    mockApi({ 'POST /guide/stream': () => s.response() });
    const steps: GuideStep[] = [];
    const answer = ask((st) => steps.push(st));
    s.push('event:reasoning\ndata:{"step":"plan"}\n\n');
    s.push('event:writing\ndata:{"step":"plan"}\n\n');
    s.push(`event:answer\ndata:${JSON.stringify(ANSWER)}\n\n`);
    await answer;
    expect(steps).toEqual([
      { kind: 'reasoning', data: { step: 'plan' } },
      { kind: 'writing', data: { step: 'plan' } },
    ]);
  });

  it('reports how the question was read and the clarifying question as steps', async () => {
    const s = liveStream();
    mockApi({ 'POST /guide/stream': () => s.response() });
    const steps: GuideStep[] = [];
    const answer = ask((st) => steps.push(st));
    s.push('event:understood\ndata:{"intent":"SUGGEST","subjects":["mcp"],"window":null}\n\n');
    s.push('event:clarify\ndata:{"question":"Which database?","options":["Postgres","MySQL"]}\n\n');
    s.push(
      `event:answer\ndata:${JSON.stringify({ ...ANSWER, status: 'NEEDS_CLARIFICATION' })}\n\n`,
    );
    await expect(answer).resolves.toMatchObject({ status: 'NEEDS_CLARIFICATION' });
    expect(steps).toEqual([
      { kind: 'understood', data: { intent: 'SUGGEST', subjects: ['mcp'], window: null } },
      { kind: 'clarify', data: { question: 'Which database?', options: ['Postgres', 'MySQL'] } },
    ]);
  });

  it('ignores an event name the contract does not define', async () => {
    const s = liveStream();
    mockApi({ 'POST /guide/stream': () => s.response() });
    const steps: GuideStep[] = [];
    const answer = ask((st) => steps.push(st));
    s.push('event:gossip\ndata:{}\n\n');
    s.push(`event:answer\ndata:${JSON.stringify(ANSWER)}\n\n`);
    await expect(answer).resolves.toMatchObject({ status: 'OK' });
    expect(steps).toEqual([]);
  });

  it('gives up after 45 s without data, the timer starting over on every chunk', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const s = liveStream();
    mockApi({ 'POST /guide/stream': () => s.response() });
    const result = ask().catch((e: unknown) => e);
    let settled = false;
    void result.then(() => {
      settled = true;
    });
    await vi.advanceTimersByTimeAsync(30_000);
    s.push('event:planned\ndata:{"searchQuery":"mcp","constraints":[],"model":"m"}\n\n');
    await vi.advanceTimersByTimeAsync(40_000);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(6_000);
    const err = await result;
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).stream).toBe('IDLE_TIMEOUT');
    await vi.waitFor(() => expect(s.state.cancelled).toBe(true));
  });

  it('keep-alive comments alone hold the stream open past 45 s', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const s = liveStream();
    mockApi({ 'POST /guide/stream': () => s.response() });
    const steps: GuideStep[] = [];
    const result = ask((st) => steps.push(st)).catch((e: unknown) => e);
    for (let i = 0; i < 6; i++) {
      await vi.advanceTimersByTimeAsync(15_000);
      s.push(':keep-alive\n\n');
    }
    await vi.advanceTimersByTimeAsync(40_000);
    s.push(`event:answer\ndata:${JSON.stringify(ANSWER)}\n\n`);
    expect(await result).toMatchObject({ status: 'OK' });
    expect(steps).toEqual([]);
  });

  it('maps an unreadable event to an ApiError and still cancels the reader', async () => {
    const s = liveStream();
    mockApi({ 'POST /guide/stream': () => s.response() });
    const result = ask().catch((e: unknown) => e);
    s.push('event:planned\ndata:{not json\n\n');
    const err = await result;
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).stream).toBe('MALFORMED');
    await vi.waitFor(() => expect(s.state.cancelled).toBe(true));
  });

  it('a stream that ends without an answer is a clear error', async () => {
    const s = liveStream();
    mockApi({ 'POST /guide/stream': () => s.response() });
    const result = ask().catch((e: unknown) => e);
    s.push('event:planned\ndata:{"searchQuery":"mcp","constraints":[],"model":"m"}\n\n');
    s.end();
    const err = await result;
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).stream).toBe('ENDED_EARLY');
  });

  it('a connection that drops mid-answer is its own error, not a generic one', async () => {
    const s = liveStream();
    mockApi({ 'POST /guide/stream': () => s.response() });
    const result = ask().catch((e: unknown) => e);
    s.push('event:planned\ndata:{"searchQuery":"mcp","constraints":[],"model":"m"}\n\n');
    await new Promise((r) => setTimeout(r, 0));
    s.drop();
    const err = await result;
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).stream).toBe('DROPPED');
  });

  it('the caller aborting cancels the reader', async () => {
    const s = liveStream();
    mockApi({ 'POST /guide/stream': () => s.response() });
    const ac = new AbortController();
    const result = ask(undefined, ac.signal).catch((e: unknown) => e);
    s.push('event:planned\ndata:{"searchQuery":"mcp","constraints":[],"model":"m"}\n\n');
    await new Promise((r) => setTimeout(r, 0));
    ac.abort();
    expect(await result).toMatchObject({ name: 'AbortError' });
    await vi.waitFor(() => expect(s.state.cancelled).toBe(true));
  });
});
