import en from '@/i18n/locales/en.json';
import {
  draftingModel,
  EXAMPLE_POOL,
  fillsBothBuckets,
  pickExamples,
  type Example,
} from './examples';

const pool: Example[] = [
  { id: 'a', path: 'USE_AI' },
  { id: 'b', path: 'USE_AI' },
  { id: 'c', path: 'USE_AI' },
  { id: 'x', path: 'BUILD_WITH_AI' },
  { id: 'y', path: 'BUILD_WITH_AI' },
];

describe('pickExamples: random examples from a pool, per bucket', () => {
  it('takes two from each bucket, people who use AI first', () => {
    const picked = pickExamples(pool, 2, () => 0);
    expect(picked.map((e) => e.path)).toEqual([
      'USE_AI',
      'USE_AI',
      'BUILD_WITH_AI',
      'BUILD_WITH_AI',
    ]);
    expect(picked.map((e) => e.id)).toEqual(['a', 'b', 'x', 'y']);
  });

  it('follows the random source, and never repeats an example', () => {
    const picked = pickExamples(pool, 2, () => 0.99);
    expect(picked.map((e) => e.id)).toEqual(['c', 'a', 'y', 'x']);
    for (let run = 0; run < 50; run++) {
      const ids = pickExamples(EXAMPLE_POOL).map((e) => e.id);
      expect(new Set(ids).size).toBe(ids.length);
    }
  });

  it('gives what a small bucket has, and nothing from an empty pool', () => {
    expect(pickExamples([{ id: 'a', path: 'USE_AI' }], 2, () => 0)).toHaveLength(1);
    expect(pickExamples([], 2)).toEqual([]);
  });

  it('every built-in example has a label and a query', () => {
    const texts = en.home.example as Record<string, { label?: string; query?: string }>;
    for (const { id } of EXAMPLE_POOL) {
      expect(texts[id]?.label, id).toBeTruthy();
      expect(texts[id]?.query, id).toBeTruthy();
    }
    expect(Object.keys(texts).sort()).toEqual(EXAMPLE_POOL.map((e) => e.id).sort());
  });
});

describe('the served pool: used only when it fills both buckets', () => {
  it('needs two examples for people who use AI and two for builders', () => {
    expect(fillsBothBuckets(pool)).toBe(true);
    expect(fillsBothBuckets(pool.filter((e) => e.id !== 'y'))).toBe(false);
    expect(fillsBothBuckets(pool.filter((e) => e.path === 'USE_AI'))).toBe(false);
    expect(fillsBothBuckets([])).toBe(false);
  });

  it('names the drafting model only when the pool names exactly one', () => {
    const marker = (models: string[]) => ({
      examples: [],
      ai: { aiGenerated: true, provider: 'nebius', models, fields: [], policy: '/mission' },
    });
    expect(draftingModel(marker(['nemotron-nano']))).toBe('nemotron-nano');
    expect(draftingModel(marker(['a', 'b']))).toBeUndefined();
    expect(draftingModel({ examples: [] })).toBeUndefined();
  });
});
