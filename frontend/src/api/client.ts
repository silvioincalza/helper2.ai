import type {
  UpcomingEventsResponse,
  Bookmark,
  BookmarkRequest,
  GuideRequest,
  GuideResponse,
  GuideStep,
  GuideStepEvent,
  IssueReportAccepted,
  IssueReportRequest,
  Me,
  Site,
  Preferences,
  PreferencesChange,
  Problem,
  RecentResponse,
  ResourceDetail,
  SetupGuide,
  ToolGuide,
  HomeExamplePool,
  SearchResponse,
  SourceInfo,
  Category,
} from './types';

import { guideStreamEventValues } from './schema';
import { SseParser } from '@/lib/sse';
import { stampReceived } from '@/lib/sourceAge';

export const API_BASE = '/api/v1';

const SIGN_IN_AGAIN_CODE = 'sign-in-again';
export const SIGN_IN_AGAIN_EVENT = 'h2:sign-in-again';

export type StreamFailure = 'IDLE_TIMEOUT' | 'MALFORMED' | 'ENDED_EARLY' | 'DROPPED';

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

export function isSignInAgain(e: unknown): boolean {
  return e instanceof ApiError && e.problem?.code === SIGN_IN_AGAIN_CODE;
}

export function csrfTokenFrom(cookies: string): string | undefined {
  for (const name of ['__Host-XSRF-TOKEN', 'XSRF-TOKEN']) {
    const m = new RegExp(`(?:^|;\\s*)${name}=([^;]*)`).exec(cookies);
    if (m?.[1]) return decodeURIComponent(m[1]);
  }
  return undefined;
}

function readCsrfToken(): string | undefined {
  try {
    return csrfTokenFrom(document.cookie);
  } catch {
    return undefined;
  }
}

async function loadCsrfToken(signal?: AbortSignal): Promise<boolean> {
  try {
    await fetch(absolute(`${API_BASE}/me`), {
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
      ...(signal ? { signal } : {}),
    });
  } catch (e) {
    if (signal?.aborted) throw e;
    return false;
  }
  return readCsrfToken() !== undefined;
}

interface RequestOptions {
  query?: URLSearchParams;
  body?: unknown;
  signal?: AbortSignal | undefined;
  accept?: string;
}

export function absolute(path: string, query?: URLSearchParams): string {
  const url = new URL(path, window.location.origin);
  if (query) url.search = query.toString();
  return url.toString();
}

export async function send(method: string, url: string, opts: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = {
    Accept: opts.accept ?? 'application/json, application/problem+json',
    'Accept-Language': document.documentElement.lang || 'en',
  };
  const token = method === 'GET' ? undefined : readCsrfToken();
  if (token) headers['X-XSRF-TOKEN'] = token;
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
  if (res.status === 403 && method !== 'GET' && !token && (await loadCsrfToken(opts.signal)))
    return send(method, url, opts);
  if (!res.ok) {
    let problem: Problem | undefined;
    try {
      if ((res.headers.get('content-type') ?? '').includes('json'))
        problem = (await res.json()) as Problem;
    } catch {}
    if (problem?.code === SIGN_IN_AGAIN_CODE) window.dispatchEvent(new Event(SIGN_IN_AGAIN_EVENT));
    throw new ApiError(res.status, problem?.title ?? `HTTP ${res.status}`, problem);
  }
  return res;
}

export async function request<T>(
  method: string,
  path: string,
  opts: RequestOptions = {},
): Promise<T> {
  const res = await send(method, absolute(`${API_BASE}${path}`, opts.query), opts);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

const STEP_EVENTS: ReadonlySet<string> = new Set(
  guideStreamEventValues.filter((e): e is GuideStepEvent => e !== 'answer' && e !== 'error'),
);

const GUIDE_IDLE_TIMEOUT_MS = 45_000;

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
    const res = await send('POST', absolute(`${API_BASE}/guide/stream`), {
      body,
      signal: ctrl.signal,
      accept: 'text/event-stream, application/problem+json',
    });
    reader = res.body?.pipeThrough(new TextDecoderStream()).getReader();
    while (reader && !answer) {
      const { done, value } = await reader.read();
      if (done) break;
      arm();
      handle(value);
    }
    if (idle) throw new ApiError(0, 'The answer stream went quiet', undefined, 'IDLE_TIMEOUT');
    signal?.throwIfAborted();
    handle('\n\n');
    if (!answer)
      throw new ApiError(0, 'The answer stream ended without an answer', undefined, 'ENDED_EARLY');
    return answer;
  } catch (e) {
    if (idle) throw new ApiError(0, 'The answer stream went quiet', undefined, 'IDLE_TIMEOUT');
    if (e instanceof ApiError || signal?.aborted) throw e;
    throw new ApiError(0, 'The answer stream dropped', undefined, 'DROPPED');
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
  listHomeExamples: (lang: string, signal?: AbortSignal) =>
    request<HomeExamplePool>('GET', '/home/examples', {
      query: new URLSearchParams({ lang }),
      signal,
    }),
  getResource: (id: string, signal?: AbortSignal) =>
    request<ResourceDetail>('GET', `/resources/${encodeURIComponent(id)}`, { signal }),
  getResourceSetup: (id: string, signal?: AbortSignal) =>
    request<SetupGuide>('GET', `/resources/${encodeURIComponent(id)}/setup`, { signal }),
  askResourceGuide: (id: string, signal?: AbortSignal) =>
    request<ToolGuide>('POST', `/resources/${encodeURIComponent(id)}/guide`, { signal }),
  reportResource: (id: string, body: IssueReportRequest) =>
    request<IssueReportAccepted>('POST', `/resources/${encodeURIComponent(id)}/reports`, { body }),
  askGuideStream,
  listSources: (signal?: AbortSignal) =>
    request<SourceInfo[]>('GET', '/sources', { signal }).then(stampReceived),
  listCategories: (signal?: AbortSignal) => request<Category[]>('GET', '/categories', { signal }),
  getMe: (signal?: AbortSignal) => request<Me>('GET', '/me', { signal }),
  getSite: (signal?: AbortSignal) => request<Site>('GET', '/site', { signal }),
  changePreferences: (body: PreferencesChange) =>
    request<Preferences>('PATCH', '/me/preferences', { body }),
  listBookmarks: (signal?: AbortSignal) => request<Bookmark[]>('GET', '/me/bookmarks', { signal }),
  mergeLocalBookmarks: (body: BookmarkRequest[]) =>
    request<Bookmark[]>('POST', '/me/bookmarks', { body }),
  addBookmark: (body: BookmarkRequest) =>
    request<Bookmark>('PUT', `/me/bookmarks/${encodeURIComponent(body.resourceId)}`, { body }),
  deleteBookmark: (resourceId: string) =>
    request<void>('DELETE', `/me/bookmarks/${encodeURIComponent(resourceId)}`),
};

export async function signOut(reload: () => void = () => window.location.reload()): Promise<void> {
  try {
    await send('POST', absolute('/logout'), {});
  } finally {
    reload();
  }
}

export async function deleteMyAccount(
  reload: () => void = () => window.location.reload(),
): Promise<void> {
  await request<void>('DELETE', '/me');
  reload();
}

export function loginUrl(providerId: string): string {
  return `/oauth2/authorization/${encodeURIComponent(providerId)}`;
}
