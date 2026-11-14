import { useEffect, useState } from 'react';
import { useMe } from '@/context/MeContext';
import { useMediaQuery } from '@/hooks/useMediaQuery';

export function useRadarStill(): boolean {
  const { me } = useMe();
  const reduced = useMediaQuery('(prefers-reduced-motion: reduce)', false);
  return reduced || (me.authenticated && me.preferences?.radarMotion === false);
}

export function useMouseOver(el: HTMLElement | null): boolean {
  const [seen, setSeen] = useState<{ el: HTMLElement; over: boolean } | null>(null);
  useEffect(() => {
    if (!el) return undefined;
    const enter = (e: Event) => setSeen({ el, over: (e as PointerEvent).pointerType !== 'touch' });
    const leave = () => setSeen({ el, over: false });
    el.addEventListener('pointerenter', enter);
    el.addEventListener('pointerleave', leave);
    return () => {
      el.removeEventListener('pointerenter', enter);
      el.removeEventListener('pointerleave', leave);
    };
  }, [el]);
  if (!el) return false;
  return seen?.el === el ? seen.over : hoveredByAMouse(el);
}

function hoveredByAMouse(el: HTMLElement): boolean {
  return Boolean(window.matchMedia?.('(hover: hover)')?.matches) && el.matches(':hover');
}
