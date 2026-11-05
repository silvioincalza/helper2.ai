import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The radar clock paints each dot through `--o` and `--pop` (HomeRadar, radarClock.ts). A rule that gives a dot a fixed
 * opacity or transform wins over the clock when it is more specific, and the dots then show before the beam reaches
 * them and never pop or fade. Only a still radar (reduced motion, or motion off) sets them outright.
 */
const css = readFileSync(join(process.cwd(), 'src/components/home/home.css'), 'utf8');

/** Every top-level rule as [selector, body], comments removed. */
function rules(): [string, string][] {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, '');
  return [...text.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((m) => [m[1]!.trim(), m[2]!]);
}

/** A selector that ends on the dot itself, not one of its parts (`__mark`, `__name`) or tones (`--answering`). */
const targetsDot = (selector: string) =>
  selector.split(',').some((s) => /\.v4-dot(:[\w-]+)?\s*$/.test(s.trim()));

describe('radar dots', () => {
  it('take their opacity and size from the clock unless the radar stands still', () => {
    const fixed = rules()
      .filter(([selector]) => targetsDot(selector) && !selector.includes('.is-still'))
      .flatMap(([selector, body]) =>
        [...body.matchAll(/(opacity|transform):\s*([^;]+);/g)]
          .filter((m) => !/var\(--(o|pop)\b/.test(m[2]!))
          .map((m) => `${selector} { ${m[1]}: ${m[2]!.trim()} }`),
      );

    expect(fixed).toEqual([]);
  });
});
