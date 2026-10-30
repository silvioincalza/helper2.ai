import type { UpcomingEventsResponse, Bookmark, BookmarkRequest, GuideRequest, GuideResponse, GuideStep, IssueReportAccepted, IssueReportRequest, Me, Problem, RecentResponse, ResourceDetail, ReviewItem, SetupGuide, ToolGuide, ToolGuideRequest, ReviewResolution, ReviewStatus, ScoutRun, SearchResponse, SourceInfo } from './types';

import { SseParser } from '@/lib/sse';

export const API_BASE = '/api/v1';

/** Why an answer stream failed on the client side; the UI words each one. */
export type StreamFailure = 'IDLE_TIMEOUT' | 'MALFORMED' | 'ENDED_EARLY';

/** RFC 9457 problem+json (or a transport failure, status 0; `stream` says which one for an event stream). */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | undefined;
  readonly stream: StreamFailure | undefined;
  constructor(status: number, message: string, problem?: Problem, stream?: StreamFailure) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
    this.stream = stream;
  }
}

export function readCsrfToken(): string | undefined {
  try {
    const m = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(document.cookie);
    return m?.[1] ? decodeURIComponent(m[1]) : undefined;
  } catch {
    return undefined;
  }
}

interface RequestOptions {
  query?: URLSearchParams;
  body?: unknown;
  signal?: AbortSignal | undefined;
  accept?: string;
}

function absolute(path: string, query?: URLSearchParams): string {
  // new URL(..., origin) keeps the same code working in browsers and in jsdom/Node tests.
  const url = new URL(path, window.location.origin);
  if (query) url.search = query.toString();
  return url.toString();
}

async function send(method: string, url: string, opts: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = {
    Accept: opts.accept ?? 'application/json, application/problem+json',
    'Accept-Language': document.documentElement.lang || 'en',
  };
  if (method !== 'GET') {
    const token = readCsrfToken();
    // Spring defers the token cookie; if absent we still send and let the server answer 403.
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  const init: RequestInit = { method, headers, credentials: 'same-origin' };
  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(opts.body);
  }
  if (opts.signal) init.signal = opts.signal;
  let res: Response;
  try {
    res = await fetch(url, init);
  } catch (e) {
    if (opts.signal?.aborted) throw e;
    throw new ApiError(0, 'Network error');
  }
  if (!res.ok) {
    let problem: Problem | undefined;
    try {
      if ((res.headers.get('content-type') ?? '').includes('json')) problem = (await res.json()) as Problem;
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(res.status, problem?.title ?? `HTTP ${res.status}`, problem);
  }
  return res;
}

async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  const res = await send(method, absolute(`${API_BASE}${path}`, opts.query), opts);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

const STEP_EVENTS = new Set(['planned', 'retrieved', 'chosen', 'validated', 'checked']);

/**
 * The server sends a ":keep-alive" comment every 15 s while a question runs (a model step can be silent for
 * longer), so this long without a byte, comments included, means the stream is stuck.
 */
export const GUIDE_IDLE_TIMEOUT_MS = 45_000;

/**
 * POST /guide/stream: reports each real step through [onStep], resolves with the final answer. No automatic
 * reconnect (unlike EventSource): every question costs model calls, so a dropped stream is an error the
 * user can retry, never a silent second question. The request is aborted after [idleMs] without data.
 */
async function askGuideStream(
  body: GuideRequest,
  onStep: (step: GuideStep) => void,
  signal?: AbortSignal,
  idleMs = GUIDE_IDLE_TIMEOUT_MS,
): Promise<GuideResponse> {
  const ctrl = new AbortController();
  let reader: ReadableStreamDefaultReader<string> | undefined;
  let idle = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  // Cancelling the reader as well as aborting settles a pending read even when the body ignores the signal.
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
  let answer: GuideResponse | undefined;
  const handle = (text: string) => {
    for (const e of parser.push(text)) {
      let data: unknown;
      try {
        data = JSON.parse(e.data);
      } catch {
        throw new ApiError(0, `Unreadable "${e.event}" event`, undefined, 'MALFORMED');
      }
      if (e.event === 'answer') answer = data as GuideResponse;
      else if (e.event === 'error') {
        const p = data as Problem;
        throw new ApiError(p.status ?? 500, p.title ?? 'Guide failed', p);
      } else if (STEP_EVENTS.has(e.event)) onStep({ kind: e.event, data } as GuideStep);
    }
  };

  arm();
  try {
    const res = await send('POST', absolute(`${API_BASE}/guide/stream`), { body, signal: ctrl.signal, accept: 'text/event-stream, application/problem+json' });
    reader = res.body?.pipeThrough(new TextDecoderStream()).getReader();
    while (reader && !answer) {
      const { done, value } = await reader.read();
      if (done) break;
      // Any bytes prove the stream alive, a keep-alive comment as much as an event.
      arm();
      handle(value);
    }
    if (idle) throw new ApiError(0, 'The answer stream went quiet', undefined, 'IDLE_TIMEOUT');
    signal?.throwIfAborted();
    handle('\n\n');
    if (!answer) throw new ApiError(0, 'The answer stream ended without an answer', undefined, 'ENDED_EARLY');
    return answer;
  } catch (e) {
    if (idle) throw new ApiError(0, 'The answer stream went quiet', undefined, 'IDLE_TIMEOUT');
    throw e;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', stop);
    void reader?.cancel().catch(() => undefined);
  }
}

export const api = {
  search: (query: URLSearchParams, signal?: AbortSignal) =>
    request<SearchResponse>('GET', '/search', { query, signal }),
  listRecent: (query: URLSearchParams, signal?: AbortSignal) =>
    request<RecentResponse>('GET', '/recent', { query, signal }),
  listUpcomingEvents: (query: URLSearchParams, signal?: AbortSignal) =>
    request<UpcomingEventsResponse>('GET', '/events/upcoming', { query, signal }),
  getResource: (id: string, signal?: AbortSignal) =>
    request<ResourceDetail>('GET', `/resources/${encodeURIComponent(id)}`, { signal }),
  getResourceSetup: (id: string, signal?: AbortSignal) =>
    request<SetupGuide>('GET', `/resources/${encodeURIComponent(id)}/setup`, { signal }),
  askResourceGuide: (id: string, body: ToolGuideRequest, signal?: AbortSignal) =>
    request<ToolGuide>('POST', `/resources/${encodeURIComponent(id)}/guide`, { body, signal }),
  reportResource: (id: string, body: IssueReportRequest) =>
    request<IssueReportAccepted>('POST', `/resources/${encodeURIComponent(id)}/reports`, { body }),
  askGuide: (body: GuideRequest, signal?: AbortSignal) => request<GuideResponse>('POST', '/guide', { body, signal }),
  askGuideStream,
  listSources: (signal?: AbortSignal) => request<SourceInfo[]>('GET', '/sources', { signal }),
  getMe: (signal?: AbortSignal) => request<Me>('GET', '/me', { signal }),
  listBookmarks: (signal?: AbortSignal) => request<Bookmark[]>('GET', '/me/bookmarks', { signal }),
  addBookmarks: (body: BookmarkRequest[]) => request<Bookmark[]>('POST', '/me/bookmarks', { body }),
  deleteBookmark: (resourceId: string) =>
    request<void>('DELETE', `/me/bookmarks/${encodeURIComponent(resourceId)}`),
  listReviewItems: (status?: ReviewStatus, signal?: AbortSignal) =>
    request<ReviewItem[]>('GET', '/admin/review-items', {
      query: new URLSearchParams(status ? { status } : {}),
      signal,
    }),
  resolveReviewItem: (id: string, body: ReviewResolution) =>
    request<ReviewItem>('PUT', `/admin/review-items/${encodeURIComponent(id)}/resolution`, { body }),
  listScoutRuns: (signal?: AbortSignal) => request<ScoutRun[]>('GET', '/admin/scout-runs', { signal }),
  startScoutRun: (sourceId?: string) =>
    request<ScoutRun[]>('POST', '/admin/scout-runs', { body: sourceId ? { sourceId } : {} }),
};

/** Spring Security logout lives outside /api/v1. */
export async function signOut(reload: () => void = () => window.location.reload()): Promise<void> {
  try {
    await send('POST', absolute('/logout'), {});
  } finally {
    reload();
  }
}

export function loginUrl(providerId: string): string {
  return `/oauth2/authorization/${encodeURIComponent(providerId)}`;
}
