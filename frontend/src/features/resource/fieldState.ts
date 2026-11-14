import type { TFunction } from 'i18next';
import type { Assertion, ResourceDetail, ValueOrigin } from '@/api/types';
import { isAiOrigin } from '@/lib/aiMark';

export const FIELDS = [
  'pricing',
  'platforms',
  'useCase',
  'license',
  'openSource',
  'audience',
  'techLevel',
] as const;
export type Field = (typeof FIELDS)[number];

const CARD_FIELD: Partial<Record<Field, string>> = { useCase: 'useCases' };

export interface DisputedValue {
  value: string;
  sourceId?: string | undefined;
  aiOrigin?: ValueOrigin | undefined;
}

export type FieldState =
  | { kind: 'values'; values: string[]; origin: ValueOrigin }
  | { kind: 'conflict'; values: DisputedValue[] }
  | { kind: 'notStated' };

function fromCard(field: Field, detail: ResourceDetail): string[] {
  const c = detail.card;
  switch (field) {
    case 'pricing':
      return c.pricing ? [c.pricing] : [];
    case 'platforms':
      return c.platforms;
    case 'useCase':
      return c.useCases;
    case 'license':
      return c.license ? [c.license] : [];
    case 'openSource':
      return c.openSource == null ? [] : [String(c.openSource)];
    case 'techLevel':
      return c.techLevel ? [c.techLevel] : [];
    case 'audience':
      return [];
  }
}

function fromEnrichment(field: Field, assertions: Assertion[]): FieldState {
  const mine = assertions.filter((a) => a.field === field && isAiOrigin(a.origin));
  const conflict = mine.filter((a) => a.verification === 'CONFLICTING');
  if (conflict.length)
    return {
      kind: 'conflict',
      values: conflict.map((a) => ({ value: a.value, aiOrigin: a.origin })),
    };
  for (const origin of ['AI_EXTRACTED', 'SELF_DECLARED'] as const) {
    const verified = mine.filter((a) => a.origin === origin && a.verification === 'VERIFIED');
    if (verified.length) return { kind: 'values', values: verified.map((a) => a.value), origin };
  }
  const estimated = mine.filter(
    (a) => a.origin === 'AI_ESTIMATED' && a.verification !== 'REJECTED',
  );
  if (estimated.length)
    return { kind: 'values', values: estimated.map((a) => a.value), origin: 'AI_ESTIMATED' };
  return { kind: 'notStated' };
}

function fromAdmin(field: Field, assertions: Assertion[]): string[] {
  return assertions
    .filter((a) => a.field === field && a.origin === 'CURATED' && a.verification === 'VERIFIED')
    .map((a) => a.value);
}

export function stateOf(field: Field, detail: ResourceDetail): FieldState {
  const curated = fromAdmin(field, detail.assertions);
  if (curated.length) return { kind: 'values', values: curated, origin: 'CURATED' };
  const disagree = detail.card.conflicts.find((c) => c.field === (CARD_FIELD[field] ?? field));
  if (disagree)
    return {
      kind: 'conflict',
      values: disagree.values.map((v) => ({ value: v.value, sourceId: v.sourceId })),
    };
  const stated = fromCard(field, detail);
  if (stated.length) return { kind: 'values', values: stated, origin: 'SOURCE_PROVIDED' };
  return fromEnrichment(field, detail.assertions);
}

export function isField(name: string): name is Field {
  return (FIELDS as readonly string[]).includes(name);
}

export function fieldWord(t: TFunction, field: Field, value: string): string {
  switch (field) {
    case 'pricing':
      return t(`pricing.${value}`, { defaultValue: value });
    case 'useCase':
      return t(`useCase.${value}`, { defaultValue: value });
    case 'techLevel':
      return t(`techLevel.${value}`, { defaultValue: value });
    case 'audience':
      return t(`detail.audience.${value}`, { defaultValue: value });
    case 'openSource':
      return value === 'true' ? t('common.yes') : value === 'false' ? t('common.no') : value;
    default:
      return value;
  }
}
