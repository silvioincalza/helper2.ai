import { useId, useLayoutEffect, type RefObject } from 'react';

type Edge = 'top' | 'bottom';
type Part = RefObject<HTMLElement | null>;

const covered: Record<Edge, Map<string, number>> = { top: new Map(), bottom: new Map() };

function publish(edge: Edge) {
  let total = 0;
  for (const height of covered[edge].values()) total += height;
  document.documentElement.style.setProperty(`--sticky-${edge}`, `${total}px`);
}

const heightOf = (part: Part | null) =>
  Math.round(part?.current?.getBoundingClientRect().height ?? 0);

export function useStickyEdge(
  edge: Edge,
  bar: Part,
  { sticks, foldsAway = null }: { sticks: boolean; foldsAway?: Part | null },
): void {
  const id = useId();
  useLayoutEffect(() => {
    const measure = () => {
      covered[edge].set(id, sticks ? Math.max(0, heightOf(bar) - heightOf(foldsAway)) : 0);
      publish(edge);
    };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    for (const part of [bar, foldsAway]) if (part?.current) observer?.observe(part.current);
    return () => {
      observer?.disconnect();
      covered[edge].delete(id);
      publish(edge);
    };
  }, [edge, id, bar, sticks, foldsAway]);
}
