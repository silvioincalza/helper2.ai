import { useCallback, useEffect, useRef } from 'react';

export function useRetry(reload: () => void, loading: boolean) {
  const focusRef = useRef<HTMLHeadingElement>(null);
  const pending = useRef<'no' | 'asked' | 'loading'>('no');
  useEffect(() => {
    if (pending.current === 'no') return;
    if (loading) {
      pending.current = 'loading';
      return;
    }
    if (pending.current === 'asked') return;
    pending.current = 'no';
    const active = document.activeElement;
    if (!active || active === document.body || active === focusRef.current)
      focusRef.current?.focus();
  });
  const retry = useCallback(() => {
    pending.current = 'asked';
    focusRef.current?.focus();
    reload();
  }, [reload]);
  return { retry, focusRef };
}
