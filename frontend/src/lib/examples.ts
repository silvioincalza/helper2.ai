import type { AudiencePath, HomeExamplePool } from '@/api/types';

/** A home example: its words live in `home.example.<id>` (label and query); the path tells the ranking who asks. */
export interface Example {
  id: string;
  path: AudiencePath;
}

/**
 * The built-in pool. Every example was checked on staging (5 Nov 2026): a clearly fitting result in the top 3 for its
 * query and path. Five for people who use AI, six for people who build with it.
 */
export const EXAMPLE_POOL: readonly Example[] = [
  { id: 'papers', path: 'USE_AI' },
  { id: 'meetings', path: 'USE_AI' },
  { id: 'images', path: 'USE_AI' },
  { id: 'background', path: 'USE_AI' },
  { id: 'video', path: 'USE_AI' },
  { id: 'postgres', path: 'BUILD_WITH_AI' },
  { id: 'vectors', path: 'BUILD_WITH_AI' },
  { id: 'agents', path: 'BUILD_WITH_AI' },
  { id: 'local', path: 'BUILD_WITH_AI' },
  { id: 'rag', path: 'BUILD_WITH_AI' },
  { id: 'coding', path: 'BUILD_WITH_AI' },
];

/** How many examples the home page shows from each bucket. */
export const PER_BUCKET = 2;

/** [count] different items of [items], in random order (a partial Fisher–Yates shuffle). */
function sample<T>(items: readonly T[], count: number, random: () => number): T[] {
  const copy = [...items];
  const n = Math.min(count, copy.length);
  for (let i = 0; i < n; i++) {
    const j = i + Math.floor(random() * (copy.length - i));
    [copy[i], copy[j]] = [copy[j] as T, copy[i] as T];
  }
  return copy.slice(0, n);
}

/**
 * The examples for one page view: [perBucket] from each bucket (people who use AI first, then builders), no
 * duplicates. A bucket with fewer examples gives what it has.
 */
export function pickExamples<T extends { path: AudiencePath }>(
  pool: readonly T[],
  perBucket = PER_BUCKET,
  random: () => number = Math.random,
): T[] {
  const bucket = (path: AudiencePath) => pool.filter((e) => e.path === path);
  return [
    ...sample(bucket('USE_AI'), perBucket, random),
    ...sample(bucket('BUILD_WITH_AI'), perBucket, random),
  ];
}

/** How long the home page waits for the served pool before it shows the built-in examples. */
export const POOL_TIMEOUT_MS = 800;

/** The served pool is used only when it fills both buckets; otherwise the page shows the built-in examples. */
export function fillsBothBuckets(
  pool: readonly { path: AudiencePath }[],
  perBucket = PER_BUCKET,
): boolean {
  const count = (path: AudiencePath) => pool.filter((e) => e.path === path).length;
  return count('USE_AI') >= perBucket && count('BUILD_WITH_AI') >= perBucket;
}

/** The model to name on an AI-drafted chip: only when the pool names exactly one, so it is the one that drafted it. */
export function draftingModel(pool: HomeExamplePool): string | undefined {
  const models = pool.ai?.models ?? [];
  return models.length === 1 ? models[0] : undefined;
}
