import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { THEMES, tokenContrast } from '@/test/contrast';

const TEXT: Array<[string, string, string]> = [
  ['secondary button', '--ink', '--surface'],
  ['ghost button on the page', '--ink', '--bg'],
  ['ghost button on hover', '--ink', '--surface'],
  ['primary button', '--on-accent', '--accent'],
  ['ink button', '--bg', '--ink'],
  ['danger button on the page', '--danger', '--bg'],
  ['danger button in a card', '--danger', '--surface'],
  ['danger button, filled', '--bg', '--danger'],
  ['disabled button', '--muted', '--surface-tint'],
  ['text button', '--ink-2', '--bg'],
  ['text button on hover', '--accent-ink', '--bg'],
  ['chip', '--ink', '--bg'],
  ['chip on hover', '--ink', '--surface'],
  ['selected chip', '--on-selected', '--selected'],
  ['field', '--ink', '--surface'],
  ['badge', '--ink', '--surface-tint'],
  ['button on a terminal panel', '--term-text', '--term-bg'],
  ['button on a terminal panel, on hover', '--term-text', '--term-bar'],
];

const UI: Array<[string, string, string]> = [
  ['control border on the page', '--border-strong', '--bg'],
  ['control border on a card', '--border-strong', '--surface'],
  ['hover border on the page', '--ink', '--bg'],
  ['hover border on a card', '--ink', '--surface'],
  ['primary hover ring', '--on-accent', '--accent'],
  ['ink hover ring', '--bg', '--ink'],
  ['danger outline', '--danger', '--bg'],
  ['danger fill hover ring', '--bg', '--danger'],
  ['focus ring on the page', '--focus', '--bg'],
  ['focus ring on a card', '--focus', '--surface'],
  ['focus ring inside a selected item', '--on-selected', '--selected'],
  ['selected chip on the page', '--selected', '--bg'],
  ['checkbox', '--ink', '--surface'],
  ['button border on a terminal panel', '--term-control', '--term-bg'],
  ['hover border on a terminal panel', '--term-text', '--term-bar'],
  ['focus ring on a terminal panel', '--term-text', '--term-bg'],
];

const LINES: Array<[string, string, string]> = [
  ['line between the rows of a terminal panel', '--term-line', '--term-bg'],
];

describe.each(Object.entries(THEMES))('shared controls, %s theme', (_, tokens) => {
  it.each(TEXT)('%s: text is at least 4.5:1', (_name, fg, bg) => {
    expect(tokenContrast(tokens, fg, bg)).toBeGreaterThanOrEqual(4.5);
  });
  it.each(UI)('%s: at least 3:1', (_name, fg, bg) => {
    expect(tokenContrast(tokens, fg, bg)).toBeGreaterThanOrEqual(3);
  });
  it.each(LINES)('%s: shows on its panel, at least 2:1', (_name, fg, bg) => {
    expect(tokenContrast(tokens, fg, bg)).toBeGreaterThanOrEqual(2);
  });
});

describe('Feature: a button on a terminal panel', () => {
  const css = readFileSync(join(process.cwd(), 'src/styles/app.css'), 'utf8');

  it('Scenario: it takes the colours of the terminal, never the ink of the page', () => {
    const rule = /\n\.btn-on-term \{([^}]*)\}/.exec(css)?.[1] ?? '';
    expect(rule).toMatch(/color: var\(--term-text\);/);
    expect(rule).toMatch(/border-color: var\(--term-control\);/);
    expect(rule).not.toMatch(/var\(--(ink|surface|bg)\)/);
  });

  it('Scenario: on hover it fills with the terminal bar colour', () => {
    const hover = /\n\.btn-on-term:hover[^{]*\{([^}]*)\}/.exec(css)?.[1] ?? '';
    expect(hover).toMatch(/background: var\(--term-bar\);/);
    expect(hover).toMatch(/border-color: var\(--term-text\);/);
  });
});
