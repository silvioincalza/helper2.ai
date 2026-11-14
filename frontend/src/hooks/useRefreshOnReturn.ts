import { useEffect, useRef } from 'react';

export function isStale(askedAt: number, now: number, minAgeMs: number): boolean {
  return now - askedAt >= minAgeMs;
}

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
