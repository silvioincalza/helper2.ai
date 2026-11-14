import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const css = readFileSync(join(process.cwd(), 'src/features/home/home.css'), 'utf8');

function rules(): [string, string][] {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, '');
  return [...text.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((m) => [m[1]!.trim(), m[2]!]);
}

const targetsDot = (selector: string) =>
  selector.split(',').some((s) => /\.h2-dot(:[\w-]+)?\s*$/.test(s.trim()));

describe('radar dots', () => {
  it('take their opacity and size from the clock unless the radar stands still or the dot is under the focus or the mouse', () => {
    const inUse = (selector: string) =>
      selector.split(',').every((s) => /\.h2-dot:(hover|focus-visible)\s*$/.test(s));
    const fixed = rules()
      .filter(
        ([selector]) => targetsDot(selector) && !selector.includes('.is-still') && !inUse(selector),
      )
      .flatMap(([selector, body]) =>
        [...body.matchAll(/(opacity|transform):\s*([^;]+);/g)]
          .filter((m) => !/var\(--(o|pop)\b/.test(m[2]!))
          .map((m) => `${selector} { ${m[1]}: ${m[2]!.trim()} }`),
      );

    expect(fixed).toEqual([]);
  });
});
