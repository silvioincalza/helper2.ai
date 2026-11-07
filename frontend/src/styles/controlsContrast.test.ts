import { THEMES, tokenContrast } from '@/test/contrast';

/**
 * The shared controls of app.css (ADR 0028 (part was 0042), decision 14) in every state, both themes: the text on a control is
 * 4.5:1 or more (WCAG 1.4.3), and what marks the control, its hover, its focus and its selected state is 3:1 or more
 * against what is around it (WCAG 1.4.11).
 */
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
  ['selected chip on the page', '--selected', '--bg'],
  ['checkbox', '--ink', '--surface'],
];

describe.each(Object.entries(THEMES))('shared controls, %s theme', (_, tokens) => {
  it.each(TEXT)('%s: text is at least 4.5:1', (_name, fg, bg) => {
    expect(tokenContrast(tokens, fg, bg)).toBeGreaterThanOrEqual(4.5);
  });
  it.each(UI)('%s: at least 3:1', (_name, fg, bg) => {
    expect(tokenContrast(tokens, fg, bg)).toBeGreaterThanOrEqual(3);
  });
});
