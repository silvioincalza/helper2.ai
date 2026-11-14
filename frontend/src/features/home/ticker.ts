import { useEffect, useState, type MouseEvent, type RefObject } from 'react';
import { useRadarStill } from './useRadarMotion';

export const TICK_EVERY_MS = 4000;
export const SCROLL_REST_MS = 1000;

export function tickerWindow<Row>(rows: readonly Row[], offset: number, count: number): Row[] {
  if (rows.length <= count) return [...rows];
  const start = ((offset % rows.length) + rows.length) % rows.length;
  return Array.from({ length: count }, (_, i) => rows[(start + i) % rows.length]!);
}

export function useTicker(
  box: RefObject<HTMLElement | null>,
  active: boolean,
  { holdOnScroll = false }: { holdOnScroll?: boolean } = {},
) {
  const [step, setStep] = useState(0);
  const still = useRadarStill();
  const [offset, setOffset] = useState(0);
  const [held, setHeld] = useState({ hover: false, focus: false, tap: false });
  const [scrolling, setScrolling] = useState(false);
  const [seen, setSeen] = useState(true);

  useEffect(() => {
    if (!holdOnScroll) return undefined;
    let rest: number | undefined;
    const onScroll = () => {
      setScrolling(true);
      window.clearTimeout(rest);
      rest = window.setTimeout(() => setScrolling(false), SCROLL_REST_MS);
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => {
      window.removeEventListener('scroll', onScroll);
      window.clearTimeout(rest);
    };
  }, [holdOnScroll]);

  useEffect(() => {
    const el = box.current;
    if (!el || typeof IntersectionObserver !== 'function') return undefined;
    const io = new IntersectionObserver(([e]) => setSeen(e?.isIntersecting ?? true));
    io.observe(el);
    return () => io.disconnect();
  }, [box, active]);

  const moving = active && !still && seen && !scrolling && !held.hover && !held.focus && !held.tap;
  useEffect(() => {
    if (!moving) return undefined;
    const id = window.setInterval(() => {
      if (document.visibilityState === 'hidden') return;
      const first = box.current?.querySelector<HTMLElement>('.h2-term-row');
      setStep(first?.getBoundingClientRect().height ?? 0);
      setOffset((o) => o + 1);
    }, TICK_EVERY_MS);
    return () => window.clearInterval(id);
  }, [moving, box]);

  const handlers = {
    onMouseEnter: () => setHeld((h) => ({ ...h, hover: true })),
    onMouseLeave: () => setHeld((h) => ({ ...h, hover: false })),
    onFocus: () => setHeld((h) => ({ ...h, focus: true })),
    onBlur: (e: { currentTarget: HTMLElement; relatedTarget: EventTarget | null }) => {
      if (!e.currentTarget.contains(e.relatedTarget as Node | null))
        setHeld((h) => ({ ...h, focus: false }));
    },
    onClick: (e: MouseEvent<HTMLElement>) => {
      if ((e.target as HTMLElement).closest('a, button')) return;
      setHeld((h) => ({ ...h, tap: !h.tap }));
    },
  };
  return { offset: active && !still ? offset : 0, step, moving, handlers };
}
