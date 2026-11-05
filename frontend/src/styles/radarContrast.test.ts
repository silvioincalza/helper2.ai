import { THEMES, tokenContrast } from '@/test/contrast';

/**
 * The radar dot colours stand out from the page: at least 3:1 against the background in both themes (WCAG 1.4.11,
 * non-text contrast). Read from tokens.css as the browser would resolve them.
 */
describe.each(Object.entries(THEMES))('radar dot colours, %s theme', (_, tokens) => {
  // Live and index: accent (filled and ring); slow or limited; failed; skipped or not checked.
  it.each(['--accent', '--status-warn-ink', '--danger', '--muted'])(
    '%s is at least 3:1 on the page',
    (token) => {
      expect(tokenContrast(tokens, token, '--bg')).toBeGreaterThanOrEqual(3);
    },
  );
});
