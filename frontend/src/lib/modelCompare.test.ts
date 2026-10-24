import { bestIn, benchmarkRows, formatSpec } from './modelCompare';
import type { ModelFamily } from '@/api/types';

const f = (id: string, specs: Record<string, string>) => ({
  id, specs: Object.entries(specs).map(([field, value]) => ({ field, value, origin: 'CURATED', verification: 'VERIFIED', evidence: [] })),
}) as unknown as ModelFamily;

describe('modelCompare', () => {
  it('ranks only stated values and needs two to call one best', () => {
    expect(bestIn([f('a', { contextTokens: '100' }), f('b', {})], 'contextTokens').size).toBe(0);
    expect([...bestIn([f('a', { outputUsdPerMTok: '10' }), f('b', { outputUsdPerMTok: '2' })], 'outputUsdPerMTok')]).toEqual(['b']);
    expect(bestIn([f('a', { flagship: 'x' }), f('b', { flagship: 'y' })], 'flagship').size).toBe(0);
  });

  it('orders benchmarks by how many families state them', () => {
    expect(benchmarkRows([f('a', { 'benchmark:A': '1', 'benchmark:B': '2' }), f('b', { 'benchmark:B': '3' })])).toEqual(['benchmark:B', 'benchmark:A']);
  });

  it('formats tokens and prices', () => {
    expect(formatSpec('contextTokens', '1000000', 'en')).toBe('1M');
    expect(formatSpec('inputUsdPerMTok', '2.5', 'en')).toBe('$2.50');
    expect(formatSpec('inputModalities', 'text,image', 'en')).toBe('text, image');
  });
});
