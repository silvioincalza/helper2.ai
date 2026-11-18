import { useLayoutEffect, type RefObject } from 'react';

export function useBarHeight(
  bar: RefObject<HTMLElement | null>,
  box: RefObject<HTMLElement | null>,
  name: string,
  key: unknown,
): void {
  useLayoutEffect(() => {
    const target = box.current;
    const measured = bar.current;
    if (!target) return;
    if (!measured) {
      target.style.removeProperty(name);
      return;
    }
    const measure = () =>
      target.style.setProperty(name, `${Math.round(measured.getBoundingClientRect().height)}px`);
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(measured);
    return () => observer?.disconnect();
  }, [bar, box, name, key]);
}
