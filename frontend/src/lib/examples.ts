import type { AudiencePath, HomeExamplePool } from '@/api/types';

export interface Example {
  id: string;
  path: AudiencePath;
}

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

const PER_BUCKET = 2;

function sample<T>(items: readonly T[], count: number, random: () => number): T[] {
  const copy = [...items];
  const n = Math.min(count, copy.length);
  for (let i = 0; i < n; i++) {
    const j = i + Math.floor(random() * (copy.length - i));
    [copy[i], copy[j]] = [copy[j] as T, copy[i] as T];
  }
  return copy.slice(0, n);
}

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

export const POOL_TIMEOUT_MS = 800;

export function fillsBothBuckets(
  pool: readonly { path: AudiencePath }[],
  perBucket = PER_BUCKET,
): boolean {
  const count = (path: AudiencePath) => pool.filter((e) => e.path === path).length;
  return count('USE_AI') >= perBucket && count('BUILD_WITH_AI') >= perBucket;
}

export function draftingModel(pool: HomeExamplePool): string | undefined {
  const models = pool.ai?.models ?? [];
  return models.length === 1 ? models[0] : undefined;
}
