import { useEffect, useState, type MouseEvent, type RefObject } from 'react';
import { useRadarStill } from './useRadarMotion';

/** One row moves up every this many milliseconds; the slide takes 700 ms (home.css), the rest of the time it stands. */
export const TICK_EVERY_MS = 5000;

/** The [count] rows to show from [rows], starting at [offset] and wrapping round to the start. */
export function tickerWindow<Row>(rows: readonly Row[], offset: number, count: number): Row[] {
  if (rows.length <= count) return [...rows];
  const start = ((offset % rows.length) + rows.length) % rows.length;
  return Array.from({ length: count }, (_, i) => rows[(start + i) % rows.length]!);
}

/**
 * The home lists as a news ticker (ADR 0042, principle 7, second exception): when a list has more rows than it shows,
 * one row leaves at the top every 5 s and the next comes in at the bottom, like a station board. It holds still:
 * while the pointer is over it or the focus is in it, after a tap on it (a second tap starts it again), while it is off
 * screen or the tab is hidden, under reduced motion and for a signed-in user who turned motion off on the account
 * page (the radar's switch). There is no Play/Pause button: hover, focus and tap are the controls (WCAG 2.2.2).
 * A tap on a link opens the link and does not count.
 */
export function useTicker(box: RefObject<HTMLElement | null>, active: boolean) {
  /** How tall the row that leaves was, so the others slide up by exactly that much. */
  const [step, setStep] = useState(0);
  const still = useRadarStill();
  const [offset, setOffset] = useState(0);
  const [held, setHeld] = useState({ hover: false, focus: false, tap: false });
  const [seen, setSeen] = useState(true);

  useEffect(() => {
    const el = box.current;
    if (!el || typeof IntersectionObserver !== 'function') return undefined;
    const io = new IntersectionObserver(([e]) => setSeen(e?.isIntersecting ?? true));
    io.observe(el);
    return () => io.disconnect();
    // The box is there only once the list has rows: look again when the ticker turns on.
  }, [box, active]);

  const moving = active && !still && seen && !held.hover && !held.focus && !held.tap;
  useEffect(() => {
    if (!moving) return undefined;
    const id = window.setInterval(() => {
      if (document.visibilityState === 'hidden') return;
      const first = box.current?.querySelector<HTMLElement>('.v4-row');
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
