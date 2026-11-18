import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { audienceValues, pricingFilterValues, techLevelFilterValues } from '@/api/schema';
import { resourceFieldValues, reviewRelationshipKindValues } from '@/api/admin-schema';
import { ANY_TEXT, templatesIn, type Template } from '@/test/templates';
import en from '@/i18n/locales/en.json';
import enAdmin from '@/i18n/locales/en.admin.json';
import enLegal from '@/i18n/locales/en.legal.json';

type Named = { prefix: string; values: readonly string[] };
type Unchecked = { prefix: string; reason: string };

const VALUES_THE_API_NAMES: Named[] = [
  { prefix: 'pricing.', values: pricingFilterValues },
  { prefix: 'techLevel.', values: techLevelFilterValues },
  { prefix: 'detail.audience.', values: audienceValues },
  { prefix: 'detail.field.', values: resourceFieldValues },
  { prefix: 'admin.review.row.relationship.', values: reviewRelationshipKindValues },
];

const VALUES_ONLY_THE_BACKEND_KNOWS: Unchecked[] = [
  { prefix: 'useCase.', reason: 'a use case is a free tag: the contract types it as a string' },
  { prefix: 'admin.budget.', reason: 'BudgetToday.name is a string in the contract' },
  {
    prefix: 'admin.research.attr.',
    reason: 'the name of a researched attribute is a string in the contract',
  },
  {
    prefix: 'admin.research.invalid.',
    reason: 'the name of a researched attribute is a string in the contract',
  },
  {
    prefix: 'admin.candidateEdit.fields.',
    reason: 'the edited fields of a candidate are read from the untyped details of a review item',
  },
];

type Lists = { named: Named[]; unchecked: Unchecked[] };
const LISTS: Lists = { named: VALUES_THE_API_NAMES, unchecked: VALUES_ONLY_THE_BACKEND_KNOWS };

const root = process.cwd();
const src = join(root, 'src');
const files = readdirSync(src, { withFileTypes: true, recursive: true })
  .filter((e) => e.isFile() && /\.tsx?$/.test(e.name) && !/\.test\.tsx?$/.test(e.name))
  .map((e) => join(e.parentPath, e.name))
  .filter((f) => !f.startsWith(join(src, 'api')) && !f.startsWith(join(src, 'test')));
const inPublicApp = (file: string) => !file.startsWith(join(src, 'admin'));
const read = (list: string[]) => list.map((f) => readFileSync(f, 'utf8')).join('\n');

type Code = { text: string; templates: Template[] };

const templates = templatesIn(root, files);
const code: Code = { text: read(files), templates };
const publicCode: Code = {
  text: read(files.filter(inPublicApp)),
  templates: templates.filter((t) => inPublicApp(t.file)),
};

function leafKeys(tree: object, prefix = ''): string[] {
  return Object.entries(tree).flatMap(([k, v]) =>
    v && typeof v === 'object' && !Array.isArray(v)
      ? leafKeys(v as object, `${prefix}${k}.`)
      : [`${prefix}${k}`],
  );
}

const PLURAL = /_(zero|one|two|few|many|other)$/;
const quotedIn = (text: string, key: string) =>
  new RegExp(`['"\`]${key.replace(/\./g, '\\.')}['"\`]`).test(text);

const escaped = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const shape = (text: string) => new RegExp(`^${text.split(ANY_TEXT).map(escaped).join('.+')}$`);

function withNamedValues(text: string, named: Named[]): string[] {
  const names = named.find((n) => text === n.prefix + ANY_TEXT);
  return names ? names.values.map((value) => names.prefix + value) : [text];
}

function reaches(template: Template, text: string, key: string, unchecked: Unchecked[]): boolean {
  if (!text.includes(ANY_TEXT))
    return text === key || (template.readsObject && key.startsWith(`${text}.`));
  return (
    unchecked.some((u) => text.startsWith(u.prefix) && key.startsWith(u.prefix)) &&
    shape(text).test(key)
  );
}

function unused(tree: object, { text, templates }: Code, { named, unchecked }: Lists): string[] {
  const reads = templates.flatMap((template) =>
    template.texts
      .flatMap((t) => withNamedValues(t, named))
      .map((t) => (key: string) => reaches(template, t, key, unchecked)),
  );
  return leafKeys(tree).filter((key) => {
    const base = key.replace(PLURAL, '');
    return !quotedIn(text, base) && !reads.some((read) => read(base));
  });
}

const unusedAnywhere = (lists: Lists) => [
  ...unused(en, publicCode, lists),
  ...unused(enLegal, publicCode, lists),
  ...unused(enAdmin, code, lists),
];

const without = <T>(list: T[], entry: T) => list.filter((e) => e !== entry);

describe('the translations', () => {
  it('of the public app have no key only the back office uses', () => {
    expect(unused(en, publicCode, LISTS)).toEqual([]);
    expect(unused(enLegal, publicCode, LISTS)).toEqual([]);
  });

  it('of the back office have no unused key', () => {
    expect(unused(enAdmin, code, LISTS)).toEqual([]);
  });

  it.each(VALUES_THE_API_NAMES)('need the values the API names under $prefix', (entry) => {
    expect(unusedAnywhere({ ...LISTS, named: without(LISTS.named, entry) })).not.toEqual([]);
  });

  it.each(VALUES_ONLY_THE_BACKEND_KNOWS)('need every key under $prefix unchecked', (entry) => {
    expect(unusedAnywhere({ ...LISTS, unchecked: without(LISTS.unchecked, entry) })).not.toEqual(
      [],
    );
  });
});
