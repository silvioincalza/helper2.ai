import { useEffect, useState, type RefObject } from 'react';
import { useMe } from '@/context/MeContext';
import { useMediaQuery } from '@/hooks/useMediaQuery';

/**
 * The radar stands still (ADR 0042): under reduced motion, and for a signed-in user who turned "Radar motion" off on
 * the account page (`preferences.radarMotion` on GET /me). No rotation, the dots at full opacity, one set stays.
 */
export function useRadarStill(): boolean {
  const { me } = useMe();
  const reduced = useMediaQuery('(prefers-reduced-motion: reduce)', false);
  return reduced || (me.authenticated && me.preferences?.radarMotion === false);
}

/** True while the search box (or anything in it) has focus: the beam and the dots hold still meanwhile. */
export function useBoxFocused(box: RefObject<HTMLElement | null>): boolean {
  const [focused, setFocused] = useState(false);
  useEffect(() => {
    const el = box.current;
    if (!el) return undefined;
    const update = () => setFocused(el.contains(document.activeElement));
    const onOut = (e: FocusEvent) => {
      if (!el.contains(e.relatedTarget as Node | null)) setFocused(false);
    };
    el.addEventListener('focusin', update);
    el.addEventListener('focusout', onOut);
    // The box may already have focus when the radar arrives (it loads on demand).
    update();
    return () => {
      el.removeEventListener('focusin', update);
      el.removeEventListener('focusout', onOut);
    };
  }, [box]);
  return focused;
}
