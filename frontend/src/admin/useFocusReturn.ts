import { useEffect, useRef } from 'react';

interface Pending {
  row: string;
  fallback: string;
}

export function useFocusReturn(waiting: boolean) {
  const rows = useRef(new Map<string, HTMLElement>());
  const fallbacks = useRef(new Map<string, HTMLElement>());
  const pending = useRef<Pending | null>(null);

  useEffect(() => {
    const target = pending.current;
    if (target === null || waiting) return;
    pending.current = null;
    (rows.current.get(target.row) ?? fallbacks.current.get(target.fallback))?.focus();
  }, [waiting]);

  const keep = (elements: Map<string, HTMLElement>, key: string) => (el: HTMLElement | null) => {
    if (el) elements.set(key, el);
    else elements.delete(key);
  };

  return {
    row: (key: string) => keep(rows.current, key),
    fallback: (key: string) => keep(fallbacks.current, key),
    returnTo: (row: string, fallback: string) => {
      pending.current = { row, fallback };
    },
  };
}
