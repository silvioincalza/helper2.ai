/**
 * Which sources the Home radar shows (ADR 0028 (part was 0042), decision 4): always 5 to 10 at a time, and every source in turn. It
 * starts with 5 sources, one after another; then each step adds one new source while it grows, up to 10, and takes
 * away the oldest while it shrinks, down to 5; then it grows again. The sources wait in a queue, shuffled once at the
 * start; a source that leaves goes to the back, so every source of a group is on the radar once before any of them
 * comes back. Pure functions: the component runs the timer. `random` is injected, so tests can fix the order.
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
  /** The sources waiting their turn, next up first. Empty before the first step. */
  queue: string[];
  growing: boolean;
}

export const EMPTY: Cycle = { shown: [], queue: [], growing: true };

/** The range the radar keeps to with `n` sources: 5-10, or fewer when there are fewer sources. */
export function bounds(n: number): { fewest: number; most: number } {
  return { fewest: Math.min(FEWEST, n), most: Math.min(MOST, n) };
}

/** How long until the next step: quicker while the first sources arrive. */
export function delayAfter(c: Cycle, n: number): number {
  return c.shown.length < bounds(n).fewest ? INTRO_MS : STEP_MS;
}

/** A random order (Fisher-Yates) of `ids`. */
function shuffled(ids: string[], random: () => number): string[] {
  const out = [...ids];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [out[i], out[j]] = [out[j]!, out[i]!];
  }
  return out;
}

/**
 * The next source to come on: the first in the queue of the group (`groupOf`) with the fewest on the radar now, so
 * live sources, collectors and discovery categories take turns whatever their numbers. Ties are drawn at random.
 */
function nextUp(
  c: Cycle,
  queue: string[],
  random: () => number,
  groupOf: (id: string) => string,
): string | undefined {
  const groups = [...new Set(queue.map(groupOf))];
  if (groups.length < 2) return queue[0];
  const onRadar = (g: string) => c.shown.filter((id) => groupOf(id) === g).length;
  const fewest = Math.min(...groups.map(onRadar));
  const tied = groups.filter((g) => onRadar(g) === fewest);
  const group = tied[Math.floor(random() * tied.length)];
  return queue.find((id) => groupOf(id) === group);
}

/**
 * One step: the next source of the queue in while growing (balanced between the groups of `groupOf`), the oldest out
 * and to the back of the queue while shrinking.
 */
export function step(
  c: Cycle,
  ids: string[],
  random: () => number,
  groupOf: (id: string) => string = () => '',
): Cycle {
  const { fewest, most } = bounds(ids.length);
  if (c.growing && c.shown.length < most) {
    const queue = c.queue.length > 0 ? c.queue : shuffled(ids, random);
    const next = nextUp(c, queue, random, groupOf);
    if (next === undefined) return c;
    const shown = [...c.shown, next];
    return { shown, queue: queue.filter((id) => id !== next), growing: shown.length < most };
  }
  if (c.shown.length <= fewest) return { ...c, growing: true };
  const [oldest, ...shown] = c.shown;
  return { shown, queue: [...c.queue, oldest!], growing: shown.length <= fewest };
}

/** A fixed random set of 7 (reduced motion, or the account setting off). */
export function stillSet(ids: string[], random: () => number): string[] {
  return shuffled(ids, random).slice(0, STILL);
}
