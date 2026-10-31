/** The media query for a visitor who asked for less motion. */
export const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';

/** The visitor asked for less motion (`prefers-reduced-motion: reduce`); false where the browser cannot tell. */
export function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(REDUCED_MOTION_QUERY).matches;
}
