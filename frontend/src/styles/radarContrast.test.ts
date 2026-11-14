import { THEMES, tokenContrast } from '@/test/contrast';

describe.each(Object.entries(THEMES))('radar dot colours, %s theme', (_, tokens) => {
  it.each(['--accent', '--status-warn-ink', '--danger', '--muted'])(
    '%s is at least 3:1 on the page',
    (token) => {
      expect(tokenContrast(tokens, token, '--bg')).toBeGreaterThanOrEqual(3);
    },
  );
});
