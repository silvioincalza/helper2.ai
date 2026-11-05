import {
  bounds,
  delayAfter,
  EMPTY,
  INTRO_MS,
  STEP_MS,
  step,
  stillSet,
  type Cycle,
} from './radarRotation';

/** A fixed random sequence, so the picks are the same on every run. */
function seeded(seed = 7): () => number {
  let x = seed;
  return () => {
    x = (x * 1103515245 + 12345) % 2147483648;
    return x / 2147483648;
  };
}

const IDS = Array.from({ length: 13 }, (_, i) => `s${i}`);

/** The cycles after each of `n` steps. */
function run(n: number, ids = IDS, random = seeded()): Cycle[] {
  const out: Cycle[] = [];
  let c = EMPTY;
  for (let i = 0; i < n; i++) out.push((c = step(c, ids, random)));
  return out;
}

/** The sources in the order they came on, over `cycles`. */
function arrivals(cycles: Cycle[]): string[] {
  const out: string[] = [];
  let before: string[] = [];
  for (const c of cycles) {
    out.push(...c.shown.filter((id) => !before.includes(id)));
    before = c.shown;
  }
  return out;
}

describe('the radar cycle (ADR 0042): always 5 to 10 sources, the oldest leaves first', () => {
  it('starts with 5 sources, one after another, quicker than the later steps', () => {
    const counts = run(5).map((c) => c.shown.length);
    expect(counts).toEqual([1, 2, 3, 4, 5]);
    expect(delayAfter(EMPTY, 13)).toBe(INTRO_MS);
    expect(delayAfter(run(4).at(-1)!, 13)).toBe(INTRO_MS);
    expect(delayAfter(run(5).at(-1)!, 13)).toBe(STEP_MS);
  });

  it('after the start the count stays between 5 and 10: up to 10, down to 5, up again', () => {
    const counts = run(60)
      .slice(4)
      .map((c) => c.shown.length);
    expect(Math.min(...counts)).toBe(5);
    expect(Math.max(...counts)).toBe(10);
    expect(counts.slice(0, 13)).toEqual([5, 6, 7, 8, 9, 10, 9, 8, 7, 6, 5, 6, 7]);
  });

  it('a new source is never one already shown', () => {
    for (const c of run(100)) expect(new Set(c.shown).size).toBe(c.shown.length);
  });

  it('the oldest leaves first', () => {
    const cycles = run(12);
    const full = cycles[9]!; // 10 shown
    expect(full.shown).toHaveLength(10);
    expect(cycles[10]!.shown).toEqual(full.shown.slice(1));
    expect(cycles[11]!.shown).toEqual(full.shown.slice(2));
  });

  it('every source takes its turn: none comes back before all the others have been on', () => {
    const order = arrivals(run(400));
    expect(order.length).toBeGreaterThan(IDS.length * 5);
    for (let i = 0; i + IDS.length <= order.length; i += IDS.length)
      expect(new Set(order.slice(i, i + IDS.length)).size).toBe(IDS.length);
  });

  it('is random in the page and fixed in tests', () => {
    expect(run(5, IDS, seeded(1))).toEqual(run(5, IDS, seeded(1)));
    expect(run(5, IDS, seeded(1))).not.toEqual(run(5, IDS, seeded(2)));
  });

  it('with fewer sources it keeps to what there is', () => {
    expect(bounds(2)).toEqual({ fewest: 2, most: 2 });
    expect(bounds(7)).toEqual({ fewest: 5, most: 7 });
    const two = run(6, ['a', 'b']);
    expect(two.at(-1)!.shown).toHaveLength(2);
    expect(run(3, [])).toEqual([EMPTY, EMPTY, EMPTY]);
  });

  it('picks new sources in turn from each group, so a big group does not crowd out the rest', () => {
    const ids = [
      ...Array.from({ length: 6 }, (_, i) => `live${i}`),
      ...Array.from({ length: 7 }, (_, i) => `index${i}`),
      ...Array.from({ length: 34 }, (_, i) => `discovery${i}`),
    ];
    const groupOf = (id: string) => id.replace(/\d+$/, '');
    const random = seeded();
    const byGroup: Record<string, string[]> = { live: [], index: [], discovery: [] };
    let c = EMPTY;
    for (let i = 0; i < 1500; i++) {
      const next = step(c, ids, random, groupOf);
      for (const id of next.shown.filter((x) => !c.shown.includes(x)))
        byGroup[groupOf(id)]!.push(id);
      c = next;
    }
    const counts = Object.values(byGroup).map((a) => a.length);
    expect(Math.max(...counts) - Math.min(...counts)).toBeLessThanOrEqual(Math.max(...counts) / 5);
    // Within a group every source takes its turn: the 34 categories all come on before any comes back.
    const categories = byGroup['discovery']!;
    for (let i = 0; i + 34 <= categories.length; i += 34)
      expect(new Set(categories.slice(i, i + 34)).size).toBe(34);
  });

  it('standing still: a fixed random set of 7', () => {
    const set = stillSet(IDS, seeded());
    expect(set).toHaveLength(7);
    expect(new Set(set).size).toBe(7);
    expect(stillSet(IDS, seeded())).toEqual(set);
  });
});
