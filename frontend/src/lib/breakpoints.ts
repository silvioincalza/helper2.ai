export const BREAKPOINTS = {
  narrow: 359,
  tiny: 420,
  phone: 520,
  small: 720,
  tablet: 768,
  split: 860,
  wide: 920,
  desk: 1100,
} as const;

type Breakpoint = keyof typeof BREAKPOINTS;

export function upTo(bp: Breakpoint): string {
  return `(max-width: ${BREAKPOINTS[bp]}px)`;
}

export function above(bp: Breakpoint): string {
  return `(min-width: ${BREAKPOINTS[bp] + 1}px)`;
}

export const HEIGHTS = {
  short: 480,
} as const;

export function upToHeight(height: keyof typeof HEIGHTS): string {
  return `(max-height: ${HEIGHTS[height]}px)`;
}
