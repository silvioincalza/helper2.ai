import { useEffect, useRef, type RefObject } from 'react';

const SLACK = 1;

function markEdges(row: HTMLElement) {
  const before = Math.abs(row.scrollLeft);
  const after = row.scrollWidth - row.clientWidth - before;
  row.toggleAttribute('data-more-start', before > SLACK);
  row.toggleAttribute('data-more-end', after > SLACK);
}

export function useOverflowEdges<T extends HTMLElement>(): RefObject<T | null> {
  const row = useRef<T>(null);
  useEffect(() => {
    const el = row.current;
    if (!el) return;
    const mark = () => markEdges(el);
    el.addEventListener('scroll', mark, { passive: true });
    const sizes = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(mark);
    sizes?.observe(el);
    return () => {
      el.removeEventListener('scroll', mark);
      sizes?.disconnect();
    };
  }, []);
  useEffect(() => {
    if (row.current) markEdges(row.current);
  });
  return row;
}
