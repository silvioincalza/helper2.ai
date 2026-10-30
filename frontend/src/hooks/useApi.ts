import { useCallback, useEffect, useState } from 'react';

interface Settled<T> {
  key: string;
  data?: T;
  error?: unknown;
}

export interface ApiState<T> {
  data: T | undefined;
  /** Last successful data while a newer request is loading (for stable UI such as facets). */
  previous: T | undefined;
  error: unknown;
  loading: boolean;
  reload: () => void;
}

export interface ApiOptions {
  /** Keep the answer in the shared recent-answers cache (default true). Off for data that changes under the user. */
  cache?: boolean;
}

/** How many recent answers are kept, and for how long, so back and forward show a page without asking again. */
export const CACHE_MAX = 50;
export const CACHE_TTL_MS = 5 * 60_000;

/** Recent successful answers by key, least recently used first (a Map keeps insertion order). */
const recent = new Map<string, { data: unknown; at: number }>();

function peek(key: string): { data: unknown } | undefined {
  const hit = recent.get(key);
  return hit && Date.now() - hit.at <= CACHE_TTL_MS ? hit : undefined;
}

function remember(key: string, data: unknown) {
  recent.delete(key);
  recent.set(key, { data, at: Date.now() });
  while (recent.size > CACHE_MAX) {
    const oldest = recent.keys().next().value;
    if (oldest === undefined) break;
    recent.delete(oldest);
  }
}

/** Empties the recent-answers cache (between tests). */
export function clearApiCache() {
  recent.clear();
}

/**
 * Fetch keyed by `key`. A new key (or reload) aborts the previous request, so stale
 * responses can never overwrite fresh ones. `fetcher` must depend only on `key`.
 * A recent answer for the same key is shown at once without a request; `reload` always asks again.
 */
export function useApi<T>(key: string, fetcher: (signal: AbortSignal) => Promise<T>, options: ApiOptions = {}): ApiState<T> {
  const useCache = options.cache ?? true;
  const [nonce, setNonce] = useState(0);
  const fullKey = `${key}#${nonce}`;
  const [settled, setSettled] = useState<Settled<T> | null>(null);
  const [lastData, setLastData] = useState<T | undefined>(undefined);
  const hit = useCache && nonce === 0 ? (peek(key) as { data: T } | undefined) : undefined;
  // Keep a cache hit as our own answer: the shared entry may expire or be evicted while this page is still open,
  // and the effect does not run again for the same key. Set during render, so the hit is never shown as loading.
  if (hit && settled?.key !== fullKey) {
    setSettled({ key: fullKey, data: hit.data });
    setLastData(hit.data);
  }

  useEffect(() => {
    if (hit) {
      remember(key, hit.data);
      return;
    }
    const ac = new AbortController();
    fetcher(ac.signal).then(
      (data) => {
        if (!ac.signal.aborted) {
          if (useCache) remember(key, data);
          setSettled({ key: fullKey, data });
          setLastData(data);
        }
      },
      (error: unknown) => {
        if (!ac.signal.aborted) setSettled({ key: fullKey, error });
      },
    );
    return () => ac.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fullKey]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  const current = settled?.key === fullKey ? settled : null;
  if (!current && hit) {
    return { data: hit.data, previous: hit.data, error: undefined, loading: false, reload };
  }
  return { data: current?.data, previous: lastData, error: current?.error, loading: current === null, reload };
}
