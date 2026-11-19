const MOST = 10;
const PHONE_MOST = 3;
const PHONE_MAX = 720;
const STILL = 7;
export const INTRO_MS = 600;
export const STEP_MS = 1600;

export interface Cycle {
  shown: string[];
  queue: string[];
  growing: boolean;
}

export const EMPTY: Cycle = { shown: [], queue: [], growing: true };

export function mostFor(width: number): number {
  return width <= PHONE_MAX ? PHONE_MOST : MOST;
}

export function bounds(n: number, cap = MOST): { fewest: number; most: number } {
  return { fewest: Math.min(Math.ceil(cap / 2), n), most: Math.min(cap, n) };
}

export function delayAfter(c: Cycle, n: number, cap = MOST): number {
  return c.shown.length < bounds(n, cap).fewest ? INTRO_MS : STEP_MS;
}

function shuffled(ids: string[], random: () => number): string[] {
  const out = [...ids];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [out[i], out[j]] = [out[j]!, out[i]!];
  }
  return out;
}

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

export function step(
  c: Cycle,
  ids: string[],
  random: () => number,
  groupOf: (id: string) => string = () => '',
  cap = MOST,
): Cycle {
  const { fewest, most } = bounds(ids.length, cap);
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

export function stillSet(ids: string[], random: () => number, cap = MOST): string[] {
  return shuffled(ids, random).slice(0, Math.min(STILL, cap));
}
