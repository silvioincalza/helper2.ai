/**
 * Which sources the Home radar shows (ADR 0042, decision 4): always 5 to 10 at a time. It starts with 5 random sources,
 * one after another; then each step adds one new random source while it grows, up to 10, and takes away the oldest
 * while it shrinks, down to 5; then it grows again. Pure functions: the component runs the timer. `random` is
 * injected, so tests can fix the order.
 */

/** The fewest and the most sources on the radar at a time, and the fixed set when it stands still. */
const FEWEST = 5;
const MOST = 10;
const STILL = 7;
/** Time between two steps while the first 5 arrive, and after that. */
export const INTRO_MS = 600;
export const STEP_MS = 1600;

export interface Cycle {
  /** The sources shown now, oldest first. */
  shown: string[];
  growing: boolean;
}

export const EMPTY: Cycle = { shown: [], growing: true };

/** The range the radar keeps to with `n` sources: 5-10, or fewer when there are fewer sources. */
export function bounds(n: number): { fewest: number; most: number } {
  return { fewest: Math.min(FEWEST, n), most: Math.min(MOST, n) };
}

/** How long until the next step: quicker while the first sources arrive. */
export function delayAfter(c: Cycle, n: number): number {
  return c.shown.length < bounds(n).fewest ? INTRO_MS : STEP_MS;
}

/**
 * The sources a new one is picked from: those not shown, of the group (`groupOf`) with the fewest on the radar now, so
 * live sources, collectors and discovery categories take turns whatever their numbers. Ties are drawn at random.
 */
function candidates(
  c: Cycle,
  ids: string[],
  random: () => number,
  groupOf: (id: string) => string,
): string[] {
  const fresh = ids.filter((id) => !c.shown.includes(id));
  const groups = [...new Set(fresh.map(groupOf))];
  if (groups.length < 2) return fresh;
  const onRadar = (g: string) => c.shown.filter((id) => groupOf(id) === g).length;
  const fewest = Math.min(...groups.map(onRadar));
  const tied = groups.filter((g) => onRadar(g) === fewest);
  const group = tied[Math.floor(random() * tied.length)];
  return fresh.filter((id) => groupOf(id) === group);
}

/**
 * One step: a new random source in while growing (balanced between the groups of `groupOf`), the oldest out while
 * shrinking.
 */
export function step(
  c: Cycle,
  ids: string[],
  random: () => number,
  groupOf: (id: string) => string = () => '',
): Cycle {
  const { fewest, most } = bounds(ids.length);
  if (c.growing && c.shown.length < most) {
    const fresh = candidates(c, ids, random, groupOf);
    const next = fresh[Math.floor(random() * fresh.length)];
    if (next === undefined) return c;
    const shown = [...c.shown, next];
    return { shown, growing: shown.length < most };
  }
  if (c.shown.length <= fewest) return { ...c, growing: true };
  const shown = c.shown.slice(1);
  return { shown, growing: shown.length <= fewest };
}

/** A fixed random set of 7 (reduced motion, or the account setting off). */
export function stillSet(ids: string[], random: () => number): string[] {
  const out = [...ids];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [out[i], out[j]] = [out[j]!, out[i]!];
  }
  return out.slice(0, STILL);
}
