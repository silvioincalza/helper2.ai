import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const SRC = join(process.cwd(), 'src');

function fadedWhileBusy(css: string): string[] {
  return [...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .filter(([, selector, body]) => /\.is-stale\b/.test(selector!) && /\bopacity\s*:/.test(body!))
    .map(([, selector]) => selector!.trim());
}

describe('content kept on screen while new content loads (WCAG 1.4.3)', () => {
  it('the check finds a rule that fades it', () => {
    expect(fadedWhileBusy('.a .is-stale { opacity: 0.55; }')).toEqual(['.a .is-stale']);
    expect(fadedWhileBusy('.a .is-stale { cursor: progress; } .b { opacity: 0.5; }')).toEqual([]);
  });

  it('no style sheet fades it: the text keeps its full contrast', () => {
    const found = readdirSync(SRC, { withFileTypes: true, recursive: true })
      .filter((e) => e.isFile() && e.name.endsWith('.css'))
      .flatMap((e) => fadedWhileBusy(readFileSync(join(e.parentPath, e.name), 'utf8')));
    expect(found).toEqual([]);
  });
});
