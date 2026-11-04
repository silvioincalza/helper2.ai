import { useEffect, useRef } from 'react';

/** True when an answer asked for at [askedAt] is older than [minAgeMs] at [now]. */
export function isStale(askedAt: number, now: number, minAgeMs: number): boolean {
  return now - askedAt >= minAgeMs;
}

/**
 * Asks again when the reader comes back to the tab ([visibilitychange] to visible) and the last answer is older
 * than [minAgeMs]. No timer: a tab nobody looks at asks nothing. The age counts from the page's first request or
 * from the last refresh this hook asked for.
 */
export function useRefreshOnReturn(reload: () => void, minAgeMs = 60_000): void {
  const askedAt = useRef(0);
  const reloadRef = useRef(reload);
  useEffect(() => {
    reloadRef.current = reload;
  }, [reload]);
  useEffect(() => {
    askedAt.current = Date.now();
    const onChange = () => {
      if (document.visibilityState !== 'visible') return;
      const now = Date.now();
      if (!isStale(askedAt.current, now, minAgeMs)) return;
      askedAt.current = now;
      reloadRef.current();
    };
    document.addEventListener('visibilitychange', onChange);
    return () => document.removeEventListener('visibilitychange', onChange);
  }, [minAgeMs]);
}
