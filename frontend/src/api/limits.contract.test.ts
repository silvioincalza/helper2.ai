import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { BULK_MAX, EXPECTED_MAX, NOTE_MAX, REASON_MAX } from '../admin/limits';
import {
  MAX_CLARIFICATION,
  MAX_DROP,
  MAX_DROPS,
  MAX_QUERY,
  MAX_QUESTION,
  MIN_QUESTION,
} from '../lib/searchState';

type Node = Record<string, unknown>;

function contract(file: string): Node {
  return parse(readFileSync(join(process.cwd(), '../api', file), 'utf8')) as Node;
}

function at(root: Node, ...path: string[]): Node {
  return path.reduce<Node>((node, key) => {
    const next = node[key];
    if (next === undefined || next === null || typeof next !== 'object')
      throw new Error(`the contract has no ${path.join('.')}`);
    return next as Node;
  }, root);
}

const common = contract('common.yaml');
const publicApi = contract('public.yaml');
const adminApi = contract('admin.yaml');

const guideRequest = at(publicApi, 'components', 'schemas', 'GuideRequest', 'properties');
const adminSchemas = at(adminApi, 'components', 'schemas');

function searchQuery(): Node {
  const parameters = at(common, 'paths', '/search', 'get').parameters as Node[];
  const q = parameters.find((p) => p.name === 'q');
  if (!q) throw new Error('the contract has no q parameter on /search');
  return q.schema as Node;
}

describe('the limits the frontend types again are the limits of the contract', () => {
  it('search query', () => {
    expect(MAX_QUERY).toBe(searchQuery().maxLength);
  });

  it('guide question', () => {
    expect(MIN_QUESTION).toBe(at(guideRequest, 'request').minLength);
    expect(MAX_QUESTION).toBe(at(guideRequest, 'request').maxLength);
  });

  it('guide clarification and dropped results', () => {
    expect(MAX_CLARIFICATION).toBe(at(guideRequest, 'clarification').maxLength);
    expect(MAX_DROPS).toBe(at(guideRequest, 'drop').maxItems);
    expect(MAX_DROP).toBe(at(guideRequest, 'drop', 'items').maxLength);
  });

  it('enrichment skip reason', () => {
    expect(REASON_MAX).toBe(at(adminSchemas, 'EnrichmentSkip', 'properties', 'reason').maxLength);
  });

  it('review decisions', () => {
    const bulk = at(adminSchemas, 'ReviewBulkResolution', 'properties');
    expect(BULK_MAX).toBe(at(bulk, 'ids').maxItems);
    expect(NOTE_MAX).toBe(at(bulk, 'note').maxLength);
    expect(NOTE_MAX).toBe(at(adminSchemas, 'ReviewResolution', 'properties', 'note').maxLength);
  });

  it('expected results of a home example', () => {
    const expected = ['HomeExampleInput', 'HomeExampleChange'].map(
      (schema) => at(adminSchemas, schema, 'properties', 'expectedResourceIds').maxItems,
    );
    expect(expected).toEqual([EXPECTED_MAX, EXPECTED_MAX]);
  });

  it('what a visitor has left today: the setup guides are told as the guide questions are', () => {
    const limits = at(common, 'components', 'schemas', 'Limits');
    const allowance = [{ $ref: '#/components/schemas/DailyAllowance' }, { type: 'null' }];
    expect(limits.required).toEqual(['guideQuestions', 'setupGuides', 'webSearches', 'resetsAt']);
    expect(at(limits, 'properties', 'guideQuestions').oneOf).toEqual(allowance);
    expect(at(limits, 'properties', 'setupGuides').oneOf).toEqual(allowance);
    expect(at(common, 'components', 'schemas', 'DailyAllowance').required).toEqual([
      'limit',
      'remaining',
    ]);
  });
});
