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
  Preferences,
  PreferencesChange,
  Problem,
  RecentResponse,
  ResourceDetail,
  ReviewItem,
  ReviewItemPage,
  SetupGuide,
  ToolGuide,
  ReviewEvidence,
  ReviewResolution,
  ReviewStatus,
  ReviewKind,
  ScoutRun,
  EnrichmentRecheck,
  AuditEntryPage,
  AdminOverview,
  EnrichmentQueuePage,
  AdminHomeExample,
  ExampleCheckOutcome,
  ExampleDrafts,
  HomeExampleChange,
  HomeExampleInput,
  HomeExamplePool,
  SearchResponse,
  SourceInfo,
} from './types';

import { guideStreamEventValues } from './schema';
import { SseParser } from '@/lib/sse';

const API_BASE = '/api/v1';

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

function readCsrfToken(): string | undefined {
  try {
    const m = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(document.cookie);
    return m?.[1] ? decodeURIComponent(m[1]) : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Spring sets the token cookie on GET /me only. A write sent before that call has answered (a shared link that asks
 * the guide on the first visit) gets 403: load the cookie and send it once more. True when a token is there now.
 */
async function loadCsrfToken(signal?: AbortSignal): Promise<boolean> {
  try {
    await fetch(absolute(`${API_BASE}/me`), {
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
      ...(signal ? { signal } : {}),
    });
  } catch (e) {
    // A cancelled question stays a cancel, not a 403.
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
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(res.status, problem?.title ?? `HTTP ${res.status}`, problem);
  }
  return res;
}

/**
 * One JSON call to the API. Exported for reads that live in a page chunk and stay out of the first download (the text
 * pages' GET /policy, ADR 0042 decision 13); everything else goes through [api].
 */
export async function request<T>(
  method: string,
  path: string,
  opts: RequestOptions = {},
): Promise<T> {
  const res = await send(method, absolute(`${API_BASE}${path}`, opts.query), opts);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

/** The step events of the stream: every event the contract names except the last one, `answer` or `error`. */
const STEP_EVENTS: ReadonlySet<string> = new Set(
  guideStreamEventValues.filter((e): e is GuideStepEvent => e !== 'answer' && e !== 'error'),
);

/**
 * The server sends a ":keep-alive" comment every 15 s while a question runs (a model step can be silent for
 * longer), so this long without a byte, comments included, means the stream is stuck.
 */
const GUIDE_IDLE_TIMEOUT_MS = 45_000;

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
    const res = await send('POST', absolute(`${API_BASE}/guide/stream`), {
      body,
      signal: ctrl.signal,
      accept: 'text/event-stream, application/problem+json',
    });
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
    if (!answer)
      throw new ApiError(0, 'The answer stream ended without an answer', undefined, 'ENDED_EARLY');
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
  /** The examples the home page picks from (ADR 0049): the same for everyone, cached for 5 minutes. */
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
  listSources: (signal?: AbortSignal) => request<SourceInfo[]>('GET', '/sources', { signal }),
  getMe: (signal?: AbortSignal) => request<Me>('GET', '/me', { signal }),
  /** A signed-in user's display settings; fields left out stay as they are. */
  changePreferences: (body: PreferencesChange) =>
    request<Preferences>('PATCH', '/me/preferences', { body }),
  listBookmarks: (signal?: AbortSignal) => request<Bookmark[]>('GET', '/me/bookmarks', { signal }),
  /** Right after sign-in: the browser's bookmarks join the account's; answers with the full list. */
  mergeLocalBookmarks: (body: BookmarkRequest[]) =>
    request<Bookmark[]>('POST', '/me/bookmarks', { body }),
  addBookmark: (body: BookmarkRequest) =>
    request<Bookmark>('PUT', `/me/bookmarks/${encodeURIComponent(body.resourceId)}`, { body }),
  deleteBookmark: (resourceId: string) =>
    request<void>('DELETE', `/me/bookmarks/${encodeURIComponent(resourceId)}`),
  listReviewItems: (
    filter: {
      status?: ReviewStatus | undefined;
      kind?: ReviewKind | undefined;
      category?: string | undefined;
    },
    page: number,
    size: number,
    signal?: AbortSignal,
  ) =>
    request<ReviewItemPage>('GET', '/admin/review-items', {
      query: new URLSearchParams({
        ...(filter.status ? { status: filter.status } : {}),
        ...(filter.kind ? { kind: filter.kind } : {}),
        ...(filter.category ? { category: filter.category } : {}),
        page: String(page),
        size: String(size),
      }),
      signal,
    }),
  /** 409 when the item was already decided (another decision, a double click, or a newer enrichment run). */
  resolveReviewItem: (id: string, body: ReviewResolution) =>
    request<ReviewItem>('PUT', `/admin/review-items/${encodeURIComponent(id)}/resolution`, {
      body,
    }),
  /** What a review item is about: its run or relationship, with the quotes behind them (ADR 0045). 404 for no item. */
  getReviewEvidence: (id: string, signal?: AbortSignal) =>
    request<ReviewEvidence>('GET', `/admin/review-items/${encodeURIComponent(id)}/evidence`, {
      signal,
    }),
  listScoutRuns: (signal?: AbortSignal) =>
    request<ScoutRun[]>('GET', '/admin/scout-runs', { signal }),
  /** Answers at once with the started run's records (RUNNING); follow them with listScoutRuns. 409 while a run holds the lock. */
  startScoutRun: (sourceId?: string) =>
    request<ScoutRun[]>('POST', '/admin/scout-runs', { body: sourceId ? { sourceId } : {} }),
  /** Re-checks the stored enrichment answers by code (ADR 0056). */
  recheckEnrichments: () => request<EnrichmentRecheck>('POST', '/admin/enrichment-recheck'),
  /** Counts for the back office landing page; the server computes them at most once a minute. */
  getAdminOverview: (signal?: AbortSignal) =>
    request<AdminOverview>('GET', '/admin/overview', { signal }),
  listAdminHomeExamples: (outcome: ExampleCheckOutcome | undefined, signal?: AbortSignal) =>
    request<AdminHomeExample[]>('GET', '/admin/home-examples', {
      query: new URLSearchParams(outcome ? { outcome } : {}),
      signal,
    }),
  /** 400 invalid-request with the broken rules in its detail; 409 duplicate. */
  addHomeExample: (body: HomeExampleInput) =>
    request<AdminHomeExample>('POST', '/admin/home-examples', { body }),
  /** 409 version-conflict when someone changed it first, 409 duplicate. */
  changeHomeExample: (id: string, body: HomeExampleChange) =>
    request<AdminHomeExample>('PUT', `/admin/home-examples/${encodeURIComponent(id)}`, { body }),
  /** One model call; the drafts wait in the review queue. 503 unavailable with the reason in its detail. */
  draftHomeExamples: () => request<ExampleDrafts>('POST', '/admin/home-examples/drafts'),
  removeHomeExample: (id: string) =>
    request<void>('DELETE', `/admin/home-examples/${encodeURIComponent(id)}`),
  /** The enrichment backlog in the Scout's order; pass the previous page's next as after. 400 for a foreign cursor. */
  listEnrichmentQueue: (after: string | undefined, size: number, signal?: AbortSignal) =>
    request<EnrichmentQueuePage>('GET', '/admin/enrichment-queue', {
      query: new URLSearchParams({ ...(after ? { after } : {}), size: String(size) }),
      signal,
    }),
  listAuditEntries: (page: number, size: number, signal?: AbortSignal) =>
    request<AuditEntryPage>('GET', '/admin/audit', {
      query: new URLSearchParams({ page: String(page), size: String(size) }),
      signal,
    }),
};

/** Spring Security logout lives outside /api/v1. */
export async function signOut(reload: () => void = () => window.location.reload()): Promise<void> {
  try {
    await send('POST', absolute('/logout'), {});
  } finally {
    reload();
  }
}

/** Deletes the signed-in user's account and bookmarks; the server ends the session, so the page starts over. */
export async function deleteMyAccount(
  reload: () => void = () => window.location.reload(),
): Promise<void> {
  await request<void>('DELETE', '/me');
  reload();
}

export function loginUrl(providerId: string): string {
  return `/oauth2/authorization/${encodeURIComponent(providerId)}`;
}
