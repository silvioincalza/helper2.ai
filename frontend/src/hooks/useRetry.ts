import { useCallback, useEffect, useRef } from 'react';

/**
 * Try again without losing the reader. [reload] asks again; focus moves at once to the heading on [focusRef] when it
 * stays on the page while the answer loads, and once the answer has settled ([loading] false) to the heading on
 * [focusRef] then: the content that loaded, or the state that came back. The Try again button unmounts with its
 * state, so without this focus would drop to the page body. Focus the user moved meanwhile is left alone.
 */
export function useRetry(reload: () => void, loading: boolean) {
  const focusRef = useRef<HTMLHeadingElement>(null);
  const pending = useRef(false);
  useEffect(() => {
    if (!pending.current || loading) return;
    pending.current = false;
    const active = document.activeElement;
    if (!active || active === document.body || active === focusRef.current) focusRef.current?.focus();
  });
  const retry = useCallback(() => {
    pending.current = true;
    focusRef.current?.focus();
    reload();
  }, [reload]);
  return { retry, focusRef };
}
