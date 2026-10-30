import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

const ROTATE_MS = 6000;

/** The first row at the top of the window. */
function topRow(el: HTMLElement): number {
  return Math.max(0, (Array.from(el.children) as HTMLElement[]).findIndex((r) => r.offsetTop >= el.scrollTop - 1));
}

/** The height of `size` rows from row `at` (fewer at the end of the list), plus the list's top border. */
function fit(el: HTMLElement, at: number, size: number): number | undefined {
  const rows = (Array.from(el.children) as HTMLElement[]).slice(at, at + size);
  const first = rows[0];
  const last = rows[rows.length - 1];
  return first && last ? last.offsetTop + last.offsetHeight - first.offsetTop + el.clientTop : undefined;
}

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * A list `size` rows tall that scrolls through the rest: by itself one page every few seconds, by hand (wheel,
 * touch, keyboard) at any time. It holds still while the pointer or focus is inside or after Pause (a touch screen
 * has no hover), and never moves by itself for anyone who asked for reduced motion. It is not a live region, so screen readers are not read every page.
 */
export function Carousel<T>({ items, size = 2, label, render }: {
  items: T[];
  size?: number;
  label: string;
  render: (item: T) => ReactNode;
}) {
  const track = useRef<HTMLUListElement>(null);
  const [height, setHeight] = useState<number>();
  const { t } = useTranslation();
  const [inside, setInside] = useState(false);
  const [paused, setPaused] = useState(false);
  const [reduced] = useState(prefersReducedMotion);
  const scrolls = items.length > size;

  // The window is as tall as the `size` rows in view, so a taller row is never cut off: measured for the first
  // rows, again when they change size (fonts, width; at most once a frame), after a scroll by hand settles, and
  // before each move.
  useLayoutEffect(() => {
    const el = track.current;
    if (!el || !scrolls) { setHeight(undefined); return; }
    const measure = () => setHeight(fit(el, topRow(el), size));
    measure();
    let settle = 0;
    const onScroll = () => { window.clearTimeout(settle); settle = window.setTimeout(measure, 150); };
    el.addEventListener('scroll', onScroll, { passive: true });
    // A resize reports every row at once, and again on each frame while the width changes: one measure per frame.
    let frame = 0;
    const onResize = () => { if (!frame) frame = requestAnimationFrame(() => { frame = 0; measure(); }); };
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(onResize);
    Array.from(el.children).forEach((r) => ro?.observe(r));
    return () => {
      el.removeEventListener('scroll', onScroll);
      window.clearTimeout(settle);
      cancelAnimationFrame(frame);
      ro?.disconnect();
    };
  }, [items, size, scrolls]);

  useEffect(() => {
    if (!scrolls || inside || paused || reduced) return;
    const id = window.setInterval(() => {
      const el = track.current;
      if (!el) return;
      const rows = Array.from(el.children) as HTMLElement[];
      const at = topRow(el);
      const next = at + size < rows.length ? at + size : 0;
      setHeight(fit(el, next, size));
      el.scrollTo?.({ top: rows[next]?.offsetTop ?? 0, behavior: 'smooth' });
    }, ROTATE_MS);
    return () => window.clearInterval(id);
  }, [scrolls, inside, paused, reduced, size]);

  return (
    <div className="carousel" onMouseEnter={() => setInside(true)} onMouseLeave={() => setInside(false)}
      onFocus={() => setInside(true)} onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget)) setInside(false); }}>
      <ul ref={track} className={`plain-list radar-list${scrolls ? ' carousel-track' : ''}`} aria-label={label}
        aria-live="off" style={height ? { height } : undefined}>
        {items.map(render)}
      </ul>
      {scrolls && !reduced && (
        <button type="button" className="btn btn-ghost carousel-toggle" onClick={() => setPaused((p) => !p)}>
          {t(paused ? 'carousel.play' : 'carousel.pause', { label })}
        </button>
      )}
    </div>
  );
}
