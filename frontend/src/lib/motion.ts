/** The visitor asked for less motion (`prefers-reduced-motion: reduce`); false where the browser cannot tell. */
export function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}
