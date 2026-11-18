import { readSse, SseParser, type SseEvent } from './sse';

describe('SseParser', () => {
  it('reads named events as Spring writes them (no space after the colon)', () => {
    const p = new SseParser();
    expect(p.push('event:planned\ndata:{"a":1}\n\nevent:answer\ndata:{"b":2}\n\n')).toEqual([
      { event: 'planned', data: '{"a":1}' },
      { event: 'answer', data: '{"b":2}' },
    ]);
  });

  it('joins events split across chunks, even inside a line or a CRLF', () => {
    const p = new SseParser();
    expect(p.push('event: chos')).toEqual([]);
    expect(p.push('en\r')).toEqual([]);
    expect(p.push('\ndata: {"proposed":3}\r\n')).toEqual([]);
    expect(p.push('\r\n')).toEqual([{ event: 'chosen', data: '{"proposed":3}' }]);
  });

  it('joins multi-line data, defaults the name, and skips comments and empty events', () => {
    const p = new SseParser();
    expect(p.push(': keep-alive\n\ndata: one\ndata: two\nid: 7\n\n')).toEqual([
      { event: 'message', data: 'one\ntwo' },
    ]);
  });
});

describe('readSse', () => {
  const encoder = new TextEncoder();

  function liveStream() {
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const state = { cancelled: false, signal: undefined as AbortSignal | undefined };
    const body = new ReadableStream<Uint8Array>({
      start: (c) => {
        controller = c;
      },
      cancel: () => {
        state.cancelled = true;
      },
    });
    return {
      state,
      open: (signal: AbortSignal) => {
        state.signal = signal;
        return Promise.resolve(new Response(body));
      },
      push: (text: string) => controller.enqueue(encoder.encode(text)),
      close: () => controller.close(),
    };
  }

  it('gives every event of a stream split across chunks, then says it ended', async () => {
    const s = liveStream();
    const events: SseEvent[] = [];
    const end = readSse(s.open, (e) => events.push(e));
    s.push('event:step\ndata:{"seq":1}\n\nevent:sta');
    s.push('tus\ndata:{"status":"DONE"}\n\n');
    s.close();
    await expect(end).resolves.toBe('ended');
    expect(events).toEqual([
      { event: 'step', data: '{"seq":1}' },
      { event: 'status', data: '{"status":"DONE"}' },
    ]);
  });

  it('skips comment lines and reads lines that end in a bare carriage return', async () => {
    const s = liveStream();
    const events: SseEvent[] = [];
    const end = readSse(s.open, (e) => events.push(e));
    s.push(': keep-alive\r\revent: step\r: still here\rdata: {"seq":1}\r\r');
    s.push('data: {"seq":2}\r');
    s.push('\r');
    s.close();
    await expect(end).resolves.toBe('ended');
    expect(events).toEqual([
      { event: 'step', data: '{"seq":1}' },
      { event: 'message', data: '{"seq":2}' },
    ]);
  });

  it('gives a last event the stream did not close with a blank line', async () => {
    const s = liveStream();
    const events: SseEvent[] = [];
    const end = readSse(s.open, (e) => events.push(e));
    s.push('event:step\ndata:{"seq":1}');
    s.close();
    await expect(end).resolves.toBe('ended');
    expect(events).toEqual([{ event: 'step', data: '{"seq":1}' }]);
  });

  it('says idle and cancels the request after the idle time without a chunk', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      const s = liveStream();
      const end = readSse(s.open, () => undefined, { idleMs: 1_000 });
      await vi.advanceTimersByTimeAsync(800);
      s.push(': keep-alive\n\n');
      await vi.advanceTimersByTimeAsync(800);
      expect(s.state.signal?.aborted).toBe(false);
      await vi.advanceTimersByTimeAsync(300);
      await expect(end).resolves.toBe('idle');
      expect(s.state.signal?.aborted).toBe(true);
      await vi.waitFor(() => expect(s.state.cancelled).toBe(true));
    } finally {
      vi.useRealTimers();
    }
  });

  it('says idle when the request itself never answers', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      const open = (signal: AbortSignal) =>
        new Promise<Response>((_resolve, reject) => {
          signal.addEventListener('abort', () => reject(new Error('aborted')));
        });
      const end = readSse(open, () => undefined, { idleMs: 1_000 });
      await vi.advanceTimersByTimeAsync(1_100);
      await expect(end).resolves.toBe('idle');
    } finally {
      vi.useRealTimers();
    }
  });

  it('stops reading once the caller has what it needs', async () => {
    const s = liveStream();
    const events: SseEvent[] = [];
    const end = readSse(s.open, (e) => events.push(e), { until: () => events.length > 0 });
    s.push('data: one\n\n');
    await expect(end).resolves.toBe('ended');
    await vi.waitFor(() => expect(s.state.cancelled).toBe(true));
  });

  it('rejects with the abort reason when the caller aborts', async () => {
    const s = liveStream();
    const ctrl = new AbortController();
    const events: SseEvent[] = [];
    const end = readSse(s.open, (e) => events.push(e), { signal: ctrl.signal });
    s.push('data: one\n\n');
    await vi.waitFor(() => expect(events).toHaveLength(1));
    ctrl.abort();
    await expect(end).rejects.toMatchObject({ name: 'AbortError' });
    expect(s.state.signal?.aborted).toBe(true);
  });

  it('rejects with the error an event handler throws', async () => {
    const s = liveStream();
    const end = readSse(s.open, () => {
      throw new Error('unreadable');
    });
    s.push('data: one\n\n');
    await expect(end).rejects.toThrow('unreadable');
  });
});
