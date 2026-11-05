/**
 * Which sources the Home radar shows (ADR 0042, decision 4): always 3 to 5 at a time. It starts with 3 random sources,
 * one after another; then each step adds one new random source while it grows, up to 5, and takes away the oldest
 * while it shrinks, down to 3; then it grows again. Pure functions: the component runs the timer. `random` is
 * injected, so tests can fix the order.
 */

/** The fewest and the most sources on the radar at a time, and the fixed set when it stands still. */
export const FEWEST = 3;
export const MOST = 5;
export const STILL = 4;
/** Time between two steps while the first 3 arrive, and after that. */
export const INTRO_MS = 600;
export const STEP_MS = 1600;

export interface Cycle {
  /** The sources shown now, oldest first. */
  shown: string[];
  growing: boolean;
}

export const EMPTY: Cycle = { shown: [], growing: true };

/** The range the radar keeps to with `n` sources: 3-5, or fewer when there are fewer sources. */
export function bounds(n: number): { fewest: number; most: number } {
  return { fewest: Math.min(FEWEST, n), most: Math.min(MOST, n) };
}

/** How long until the next step: quicker while the first sources arrive. */
export function delayAfter(c: Cycle, n: number): number {
  return c.shown.length < bounds(n).fewest ? INTRO_MS : STEP_MS;
}

/** One step: a new random source in while growing, the oldest out while shrinking. */
export function step(c: Cycle, ids: string[], random: () => number): Cycle {
  const { fewest, most } = bounds(ids.length);
  if (c.growing && c.shown.length < most) {
    const fresh = ids.filter((id) => !c.shown.includes(id));
    const next = fresh[Math.floor(random() * fresh.length)];
    if (next === undefined) return c;
    const shown = [...c.shown, next];
    return { shown, growing: shown.length < most };
  }
  if (c.shown.length <= fewest) return { ...c, growing: true };
  const shown = c.shown.slice(1);
  return { shown, growing: shown.length <= fewest };
}

/** A fixed random set of 4 (reduced motion, or the account setting off). */
export function stillSet(ids: string[], random: () => number): string[] {
  const out = [...ids];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [out[i], out[j]] = [out[j]!, out[i]!];
  }
  return out.slice(0, STILL);
}
