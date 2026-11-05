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

describe('the radar cycle (ADR 0042): always 3 to 5 sources, the oldest leaves first', () => {
  it('starts with 3 sources, one after another, quicker than the later steps', () => {
    const [a, b, c] = run(3);
    expect([a!.shown.length, b!.shown.length, c!.shown.length]).toEqual([1, 2, 3]);
    expect(delayAfter(EMPTY, 13)).toBe(INTRO_MS);
    expect(delayAfter(c!, 13)).toBe(STEP_MS);
  });

  it('after the start the count stays between 3 and 5: up to 5, down to 3, up again', () => {
    const counts = run(40)
      .slice(2)
      .map((c) => c.shown.length);
    expect(Math.min(...counts)).toBe(3);
    expect(Math.max(...counts)).toBe(5);
    expect(counts.slice(0, 9)).toEqual([3, 4, 5, 4, 3, 4, 5, 4, 3]);
  });

  it('a new source is never one already shown', () => {
    for (const c of run(60)) expect(new Set(c.shown).size).toBe(c.shown.length);
  });

  it('the oldest leaves first', () => {
    const cycles = run(8);
    const full = cycles[4]!; // 5 shown
    expect(full.shown).toHaveLength(5);
    expect(cycles[5]!.shown).toEqual(full.shown.slice(1));
    expect(cycles[6]!.shown).toEqual(full.shown.slice(2));
  });

  it('is random in the page and fixed in tests', () => {
    expect(run(5, IDS, seeded(1))).toEqual(run(5, IDS, seeded(1)));
    expect(run(5, IDS, seeded(1))).not.toEqual(run(5, IDS, seeded(2)));
  });

  it('with fewer sources it keeps to what there is', () => {
    expect(bounds(2)).toEqual({ fewest: 2, most: 2 });
    const two = run(6, ['a', 'b']);
    expect(two.at(-1)!.shown).toHaveLength(2);
    expect(run(3, [])).toEqual([EMPTY, EMPTY, EMPTY]);
  });

  it('standing still: a fixed random set of 4', () => {
    const set = stillSet(IDS, seeded());
    expect(set).toHaveLength(4);
    expect(new Set(set).size).toBe(4);
    expect(stillSet(IDS, seeded())).toEqual(set);
  });
});
