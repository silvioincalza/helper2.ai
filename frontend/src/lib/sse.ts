export interface SseEvent {
  event: string;
  data: string;
}

export class SseParser {
  private buffer = '';
  private event = '';
  private data: string[] = [];

  push(text: string): SseEvent[] {
    this.buffer += text;
    const out: SseEvent[] = [];
    let nl: number;
    while ((nl = this.buffer.search(/\r\n|\r|\n/)) !== -1) {
      const line = this.buffer.slice(0, nl);
      const width = this.buffer.startsWith('\r\n', nl) ? 2 : 1;
      if (width === 1 && this.buffer[nl] === '\r' && nl === this.buffer.length - 1) break;
      this.buffer = this.buffer.slice(nl + width);
      this.line(line, out);
    }
    return out;
  }

  private line(line: string, out: SseEvent[]) {
    if (line === '') {
      if (this.data.length > 0)
        out.push({ event: this.event || 'message', data: this.data.join('\n') });
      this.event = '';
      this.data = [];
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') this.event = value;
    else if (field === 'data') this.data.push(value);
  }
}

export const SSE_IDLE_TIMEOUT_MS = 45_000;

export type SseEnd = 'ended' | 'idle';

interface ReadSseOptions {
  signal?: AbortSignal | undefined;
  idleMs?: number;
  until?: () => boolean;
}

export async function readSse(
  open: (signal: AbortSignal) => Promise<Response>,
  onEvent: (event: SseEvent) => void,
  { signal, idleMs = SSE_IDLE_TIMEOUT_MS, until = () => false }: ReadSseOptions = {},
): Promise<SseEnd> {
  const ctrl = new AbortController();
  let reader: ReadableStreamDefaultReader<string> | undefined;
  let idle = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const stop = () => {
    ctrl.abort();
    void reader?.cancel().catch(() => undefined);
  };
  const arm = () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      idle = true;
      stop();
    }, idleMs);
  };
  signal?.addEventListener('abort', stop, { once: true });
  if (signal?.aborted) stop();

  const parser = new SseParser();
  const handle = (text: string) => parser.push(text).forEach(onEvent);

  arm();
  try {
    const res = await open(ctrl.signal);
    reader = res.body?.pipeThrough(new TextDecoderStream()).getReader();
    while (reader && !until()) {
      const { done, value } = await reader.read();
      if (done) break;
      arm();
      handle(value);
    }
    if (idle) return 'idle';
    signal?.throwIfAborted();
    handle('\n\n');
    return 'ended';
  } catch (e) {
    if (idle) return 'idle';
    throw e;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', stop);
    void reader?.cancel().catch(() => undefined);
  }
}
