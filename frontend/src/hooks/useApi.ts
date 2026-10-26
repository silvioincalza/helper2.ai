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

/**
 * Fetch keyed by `key`. A new key (or reload) aborts the previous request, so stale
 * responses can never overwrite fresh ones. `fetcher` must depend only on `key`.
 */
export function useApi<T>(key: string, fetcher: (signal: AbortSignal) => Promise<T>): ApiState<T> {
  const [nonce, setNonce] = useState(0);
  const fullKey = `${key}#${nonce}`;
  const [settled, setSettled] = useState<Settled<T> | null>(null);
  const [lastData, setLastData] = useState<T | undefined>(undefined);

  useEffect(() => {
    const ac = new AbortController();
    fetcher(ac.signal).then(
      (data) => {
        if (!ac.signal.aborted) {
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
  return { data: current?.data, previous: lastData, error: current?.error, loading: current === null, reload };
}
