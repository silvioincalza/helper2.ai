import { useCallback, useEffect, useState } from 'react';

interface Settled<T> {
  key: string;
  data?: T;
  error?: unknown;
}

interface ApiState<T> {
  data: T | undefined;
  previous: T | undefined;
  error: unknown;
  loading: boolean;
  reload: () => void;
}

interface ApiOptions {
  cache?: boolean;
}

const CACHE_MAX = 50;
const CACHE_TTL_MS = 5 * 60_000;

const recent = new Map<string, { data: unknown; at: number }>();

function peek(key: string): { data: unknown } | undefined {
  const hit = recent.get(key);
  return hit && Date.now() - hit.at <= CACHE_TTL_MS ? hit : undefined;
}

export function remember(key: string, data: unknown) {
  recent.delete(key);
  recent.set(key, { data, at: Date.now() });
  while (recent.size > CACHE_MAX) {
    const oldest = recent.keys().next().value;
    if (oldest === undefined) break;
    recent.delete(oldest);
  }
}

function touch(key: string) {
  const entry = recent.get(key);
  if (!entry) return;
  recent.delete(key);
  recent.set(key, entry);
}

export function recentAnswer<T>(key: string): T | undefined {
  return peek(key)?.data as T | undefined;
}

export function clearApiCache() {
  recent.clear();
}

export function useApi<T>(
  key: string,
  fetcher: (signal: AbortSignal) => Promise<T>,
  options: ApiOptions = {},
): ApiState<T> {
  const useCache = options.cache ?? true;
  const [reloads, setReloads] = useState<{ key: string; n: number }>({ key, n: 0 });
  const nonce = reloads.key === key ? reloads.n : 0;
  const fullKey = `${key}#${nonce}`;
  const [settled, setSettled] = useState<Settled<T> | null>(null);
  const [lastData, setLastData] = useState<T | undefined>(undefined);
  const hit = useCache && nonce === 0 ? (peek(key) as { data: T } | undefined) : undefined;
  if (hit && settled?.key !== fullKey) {
    setSettled({ key: fullKey, data: hit.data });
    setLastData(hit.data);
  }

  useEffect(() => {
    if (hit) {
      touch(key);
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

  const reload = useCallback(
    () => setReloads((r) => ({ key, n: (r.key === key ? r.n : 0) + 1 })),
    [key],
  );
  const current = settled?.key === fullKey ? settled : null;
  if (!current && hit) {
    return { data: hit.data, previous: hit.data, error: undefined, loading: false, reload };
  }
  return {
    data: current?.data,
    previous: lastData,
    error: current?.error,
    loading: current === null,
    reload,
  };
}
