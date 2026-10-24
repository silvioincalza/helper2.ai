import type { Assertion, ModelFamily } from '@/api/types';

/** Spec rows in display order. Benchmarks (`benchmark:<name>`) follow, as found in the selected families. */
export const SPEC_ROWS = [
  'flagship', 'openWeights', 'contextTokens', 'maxOutputTokens', 'inputUsdPerMTok', 'outputUsdPerMTok',
  'inputModalities', 'outputModalities', 'license', 'knowledgeCutoff', 'released',
] as const;

/** Which way is better for a numeric row; rows not listed are not ranked. */
const DIRECTION: Record<string, 'high' | 'low'> = {
  contextTokens: 'high', maxOutputTokens: 'high', inputUsdPerMTok: 'low', outputUsdPerMTok: 'low',
};

export const BENCHMARK_PREFIX = 'benchmark:';

export function spec(family: ModelFamily, field: string): Assertion | undefined {
  return family.specs.find((a) => a.field === field && a.verification === 'VERIFIED');
}

/** Benchmark rows stated for at least one selected family, those stated for more families first. */
export function benchmarkRows(families: ModelFamily[]): string[] {
  const count = new Map<string, number>();
  for (const f of families) {
    for (const a of f.specs) {
      if (a.field.startsWith(BENCHMARK_PREFIX)) count.set(a.field, (count.get(a.field) ?? 0) + 1);
    }
  }
  return [...count.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([k]) => k);
}

/**
 * Ids of the families with the best stated value in a row, or none. Only values a source stated
 * take part, and a row needs at least two of them: a lone value is not "best", it is the only one.
 * Benchmarks rank higher-is-better; scores are published by each provider on its own setup, so the
 * page says they are not strictly comparable.
 */
export function bestIn(families: ModelFamily[], field: string): Set<string> {
  const direction = field.startsWith(BENCHMARK_PREFIX) ? 'high' : DIRECTION[field];
  if (!direction) return new Set();
  const values = families
    .map((f) => ({ id: f.id, n: Number.parseFloat(spec(f, field)?.value ?? '') }))
    .filter((v) => Number.isFinite(v.n));
  if (values.length < 2) return new Set();
  const target = direction === 'high' ? Math.max(...values.map((v) => v.n)) : Math.min(...values.map((v) => v.n));
  return new Set(values.filter((v) => v.n === target).map((v) => v.id));
}

/** "1000000" -> "1M", "128000" -> "128K" for token counts; prices as "$2.50". Other values unchanged. */
export function formatSpec(field: string, value: string, lang: string): string {
  const n = Number(value);
  // Non-numeric values are shown as stated ("coming_soon" -> "coming soon", "text,image" -> "text, image").
  if (!Number.isFinite(n) || value.trim() === '') return value.replace(/_/g, ' ').split(',').join(', ');
  if (field === 'contextTokens' || field === 'maxOutputTokens') {
    return new Intl.NumberFormat(lang, { notation: 'compact', maximumFractionDigits: 1 }).format(n);
  }
  if (field === 'inputUsdPerMTok' || field === 'outputUsdPerMTok') {
    return new Intl.NumberFormat(lang, { style: 'currency', currency: 'USD', maximumFractionDigits: 3 }).format(n);
  }
  return value;
}
