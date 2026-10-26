import { vi } from 'vitest';
import { ANON, SOURCES, makeRecent, makeSearch } from './fixtures';

export type Handler = (req: { url: URL; method: string; body: unknown; headers: Headers }) => Response | Promise<Response> | unknown;
export interface Call { method: string; path: string; search: string; body: unknown; headers: Headers }

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
export const problem = (status: number, title: string, detail?: string) =>
  new Response(JSON.stringify({ type: 'about:blank', title, status, detail }), { status, headers: { 'content-type': 'application/problem+json' } });

/** Stub global fetch. Keys are "METHOD /path" (path without /api/v1 prefix). Unknown routes -> 404 problem. */
export function mockApi(handlers: Record<string, Handler> = {}) {
  const calls: Call[] = [];
  const all: Record<string, Handler> = {
    'GET /me': () => ANON,
    'GET /sources': () => SOURCES,
    'GET /search': () => makeSearch(),
    'GET /recent': () => makeRecent(),
    'GET /events/upcoming': () => ({ items: [], generatedAt: '2026-10-27T09:00:00Z' }),
    'GET /me/bookmarks': () => problem(401, 'Unauthorized'),
    ...handlers,
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url);
    const method = init?.method ?? 'GET';
    const path = url.pathname.replace(/^\/api\/v1/, '');
    const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined;
    const headers = new Headers(init?.headers);
    calls.push({ method, path, search: url.search, body, headers });
    const h = all[`${method} ${path}`] ?? Object.entries(all).find(([k]) => {
      const [m, p] = k.split(' ');
      return m === method && p?.endsWith('/*') && path.startsWith(p.slice(0, -1));
    })?.[1];
    if (!h) return problem(404, 'Not found');
    const out = await h({ url, method, body, headers });
    if (out instanceof Response) return out;
    return out === undefined ? new Response(null, { status: 204 }) : json(out);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { calls, fetchMock, callsTo: (m: string, p: string) => calls.filter((c) => c.method === m && c.path === p) };
}

/** A POST /guide/stream body: the given steps, then the answer, as Spring writes server-sent events. */
export const sse = (answer: unknown, steps: { event: string; data: unknown }[] = []) =>
  new Response(
    [...steps, { event: 'answer', data: answer }].map((e) => `event:${e.event}\ndata:${JSON.stringify(e.data)}\n\n`).join(''),
    { status: 200, headers: { 'content-type': 'text/event-stream' } },
  );
