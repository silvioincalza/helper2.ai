import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import en from './locales/en.json';
import enLegal from './locales/en.legal.json';

type Text = { key: string; text: string };

function texts(tree: unknown, prefix = ''): Text[] {
  if (typeof tree === 'string') return [{ key: prefix, text: tree }];
  if (tree === null || typeof tree !== 'object') return [];
  return Object.entries(tree).flatMap(([k, v]) => texts(v, prefix ? `${prefix}.${k}` : k));
}

const PUBLIC = texts(en);
const LEGAL = texts(enLegal);
const ALL = [...PUBLIC, ...LEGAL];

const textOf = (key: string) => PUBLIC.find((t) => t.key === key)?.text;
const keysWith = (list: Text[], pattern: RegExp) =>
  list.filter((t) => pattern.test(t.text)).map((t) => t.key);

const COMMAND_LINE = /^helper2 [a-z]/;
const BARE_NAME = /helper2(?![.\w@-])/;

const UK_SPELLING =
  /\b(\w+is(e|es|ed|ing|ation|ations)|analys(e|ed|es|ing)|licen[c]e\w*|\w*colour\w*|\w*favour\w*|behaviour\w*|catalogue\w*|centre\w*|labell\w+|cancell\w+|modell\w+|travell\w+|programme\w*|grey|whilst|amongst|judgement\w*|artefact\w*)\b/gi;
const SAME_IN_US = new Set([
  'advertise',
  'advertised',
  'advertising',
  'advise',
  'arise',
  'comprise',
  'concise',
  'devise',
  'exercise',
  'expertise',
  'noise',
  'otherwise',
  'precise',
  'premise',
  'premises',
  'promise',
  'promised',
  'promises',
  'raise',
  'raised',
  'revise',
  'rise',
  'rises',
  'supervise',
  'surprise',
  'wise',
]);
const ukWords = (text: string) =>
  [...text.matchAll(UK_SPELLING)].map((m) => m[0]).filter((w) => !SAME_IN_US.has(w.toLowerCase()));

describe('Feature: one name, one spelling, plain words', () => {
  it('Scenario: the product is helper2.ai, never with a capital H', () => {
    expect(keysWith(ALL, /Helper2/)).toEqual([]);
  });

  it('Scenario: a bare "helper2" is a command line, not the name of the product', () => {
    const bare = ALL.filter((t) => BARE_NAME.test(t.text) && !COMMAND_LINE.test(t.text));
    expect(bare.map((t) => t.key)).toEqual([]);
  });

  it('Scenario: the browser tab says helper2.ai before the page sets its title', () => {
    const page = readFileSync(join(process.cwd(), 'index.html'), 'utf8');
    expect(page).toContain('<title>helper2.ai</title>');
  });

  it('Scenario: every text is in US English', () => {
    const uk = ALL.flatMap((t) => ukWords(t.text).map((word) => `${t.key}: ${word}`));
    expect(uk).toEqual([]);
  });

  it('Scenario: the public text never names the Scout', () => {
    expect(keysWith(PUBLIC, /Scout/)).toEqual([]);
  });

  it('Scenario: a value no source states says "Not stated", never "Unknown"', () => {
    for (const key of ['common.unknown', 'pricing.UNKNOWN', 'techLevel.UNKNOWN'])
      expect(textOf(key)).toBe('Not stated');
    expect(textOf('timeline.unknown')).toBe('Date not stated');
    expect(textOf('lists.noDate')).toBe('date not stated');
    expect(textOf('how.rules.notStated.body')).toMatch(
      /^If no source gave a fact, it says Not stated\./,
    );
    expect(keysWith(PUBLIC, /\bUnknown\b/)).toEqual([]);
  });

  it('Scenario: "the guide" is the picks guide; the setup feature is "setup steps"', () => {
    const setup = PUBLIC.filter((t) => t.key.startsWith('setup.') && t.key !== 'setup.cost');
    expect(keysWith(setup, /\bguides?\b/i)).toEqual([]);
    expect(textOf('setup.stepsError')).toBe('The setup steps could not be loaded. Try again.');
    expect(textOf('setup.status.LLM_UNAVAILABLE')).toBe(
      'Setup steps are not available right now. Try again later.',
    );
  });

  it('Scenario: News means stories; the home list of new entries is New in the catalog', () => {
    expect(textOf('board.news.title')).toBe('New in the catalog');
    expect(textOf('lists.tabs')).toBe('New in the catalog and Coming up');
    expect(textOf('nav.news')).toBe('News');
  });

  it.each([
    [
      'home.lede',
      'One search across the world of AI for models, apps, MCP servers and papers. Ask the guide for a pick with proof.',
    ],
    ['home.hint', 'Enter searches. Shift+Enter asks the guide for picks with proof.'],
    ['nav.theme.switchTo', 'Switch to {{theme}} theme'],
    ['sort.RELEVANCE', 'Best match'],
    [
      'results.fanOut_other',
      '{{ok}} of {{total}} sources answered · {{count}} results, duplicates removed',
    ],
    ['guide.searchedFor', 'Searched the sources for: {{q}}'],
    ['guide.status.NO_CANDIDATES', 'No source had a match. Try different words, or press Search.'],
    [
      'results.notice.CACHE_UNAVAILABLE',
      'Every source was searched live, so this search may be slower.',
    ],
    [
      'results.notice.INDEX_UNAVAILABLE',
      'Our own index did not answer, so “first seen” dates are missing for this search.',
    ],
    ['servedFrom.STALE_CACHE', 'Older saved copy'],
    ['picks.none', 'No pick. No result has a sentence that proves it fits.'],
    ['enrichment.NOT_RUN', 'No AI summary yet.'],
    ['enrichment.LLM_UNAVAILABLE', 'The AI model was not available, so there is no AI summary.'],
    [
      'detail.summaryMeta',
      'Written by {{model}} on {{date}}. Each sentence links to its evidence.',
    ],
    ['detail.fields', 'All details'],
    ['detail.sourceRecords', 'Source records'],
    ['detail.col.fingerprint', 'Content hash'],
    ['detail.col.externalId', 'ID at the source'],
    [
      'guide.notice.networkQuotaBackAt',
      "Your network has used today's AI answers. Showing the top search results instead. AI answers are back at {{time}}.",
    ],
    [
      'guide.notice.dailyQuotaBackTomorrowAt',
      "You have used today's AI answers. Showing the top search results instead. AI answers are back tomorrow at {{time}}.",
    ],
    [
      'how.lede',
      'Search first, an AI model second, a checker last. The model never decides what is true.',
    ],
    ['search.clear', 'Clear search'],
    ['report.field', 'Which detail is wrong?'],
    [
      'results.errorBody',
      'We could not reach the helper2.ai service. Nothing was searched. Check your connection, then try again.',
    ],
    ['account.radarMotion', 'Home page motion: radar and lists'],
    ['results.fromCatalog', 'helper2.ai catalog'],
    ['consent.more', 'How we use analytics'],
  ])('Scenario: %s says what was agreed', (key, text) => {
    expect(textOf(key)).toBe(text);
  });

  it('Scenario: the consent text names the footer link, and the sentence does not run into the next link', () => {
    expect(textOf('consent.text')).toBe(
      'If you accept, Google Analytics sets cookies to count page visits by country. It never gets your searches or guide questions. Search works the same either way. You can change your answer in Cookie settings in the footer.',
    );
    expect(textOf('consent.text')).toContain(textOf('footer.cookieSettings'));
  });
});

describe('Feature: every AI label has one look', () => {
  const src = join(process.cwd(), 'src');
  const css = readdirSync(src, { withFileTypes: true, recursive: true })
    .filter((e) => e.isFile() && e.name.endsWith('.css'))
    .map((e) => readFileSync(join(e.parentPath, e.name), 'utf8'))
    .join('\n');

  it('Scenario: one rule sets the mark: a target of 24px or more, words of 12px or more', () => {
    const rules = [...css.matchAll(/(?:^|\n)\.ai-label \{([^}]*)\}/g)];
    expect(rules).toHaveLength(1);
    expect(rules[0]![1]).toMatch(/min-width: 24px/);
    expect(rules[0]![1]).toMatch(/min-height: 24px/);
    expect(rules[0]![1]).toMatch(/font: 500 var\(--text-12\) \/ 1rem var\(--font-mono\)/);
  });

  it('Scenario: the three AI marks are a full, a half and a hollow dot', () => {
    expect(css).toMatch(/\.ai-dot--written \{[^}]*background: var\(--accent-ink\)/);
    expect(css).toMatch(/\.ai-dot--read \{[^}]*linear-gradient/);
    expect(css).toMatch(/\.ai-dot--estimated \{[^}]*background: transparent/);
  });

  it('Scenario: the old label styles are gone', () => {
    expect(css).not.toMatch(/h2-row__ai|home-examples__ai/);
    expect(css).not.toMatch(/\.h2-picks__ai \{[^}]*font/);
  });
});
