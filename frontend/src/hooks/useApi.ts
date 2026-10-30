import { useCallback, useEffect, useState } from 'react';

/** Responses kept for back navigation, most recently used last. */
export const API_CACHE_SIZE = 20;
/** A cached response younger than this is shown without a request; an older one is shown and refreshed. */
export const API_FRESH_MS = 60_000;

interface Cached {
  data: unknown;
  at: number;
}

/** Settled responses by request key, for this page load only: a Map keeps insertion order, so the first key is the oldest. */
const cache = new Map<string, Cached>();

function remember(key: string, data: unknown, at = Date.now()) {
  cache.delete(key);
  cache.set(key, { data, at });
  if (cache.size > API_CACHE_SIZE) cache.delete(cache.keys().next().value as string);
}

/** Tests start from an empty cache. */
export function clearApiCache() {
  cache.clear();
}

interface Settled<T> {
  key: string;
  data?: T;
  error?: unknown;
}

export interface ApiState<T> {
  data: T | undefined;
  /** Last data shown while a newer request is loading (for stable UI such as facets). */
  previous: T | undefined;
  error: unknown;
  loading: boolean;
  reload: () => void;
}

/**
 * Fetch keyed by `key`. A new key (or reload) aborts the previous request, so stale responses can never overwrite
 * fresh ones. `fetcher` must depend only on `key`. A key answered in this page load renders its last response at once
 * (back navigation shows the same results); after API_FRESH_MS it is also refreshed in the background, and a failed
 * refresh keeps the shown response. `reload` always asks the server.
 */
export function useApi<T>(key: string, fetcher: (signal: AbortSignal) => Promise<T>): ApiState<T> {
  const [nonce, setNonce] = useState(0);
  const fullKey = `${key}#${nonce}`;
  const [settled, setSettled] = useState<Settled<T> | null>(null);
  const [lastData, setLastData] = useState<T | undefined>(undefined);

  useEffect(() => {
    const hit = cache.get(key);
    if (hit) remember(key, hit.data, hit.at);
    if (hit && Date.now() - hit.at < API_FRESH_MS) return;
    const ac = new AbortController();
    fetcher(ac.signal).then(
      (data) => {
        if (ac.signal.aborted) return;
        remember(key, data);
        setSettled({ key: fullKey, data });
      },
      (error: unknown) => {
        if (!ac.signal.aborted && !hit) setSettled({ key: fullKey, error });
      },
    );
    return () => ac.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fullKey]);

  const reload = useCallback(() => {
    cache.delete(key);
    setNonce((n) => n + 1);
  }, [key]);
  const current = settled?.key === fullKey ? settled : null;
  const hit = current ? undefined : cache.get(key);
  const data = (current ? current.data : hit?.data) as T | undefined;
  if (data !== undefined && data !== lastData) setLastData(data);
  return { data, previous: lastData, error: current?.error, loading: current === null && hit === undefined, reload };
}
