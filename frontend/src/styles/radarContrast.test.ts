import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The radar dot colours stand out from the page: at least 3:1 against the background in both themes (WCAG 1.4.11,
 * non-text contrast). Read from tokens.css as the browser would resolve them.
 */
const css = readFileSync(join(process.cwd(), 'src/styles/tokens.css'), 'utf8');

/** The custom properties declared in the first block that starts with `selector {`. */
function block(selector: string, after = 0): Record<string, string> {
  const start = css.indexOf(`${selector} {`, after);
  const body = css.slice(start, css.indexOf('}', start));
  return Object.fromEntries(
    [...body.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map((m) => [m[1]!, m[2]!.trim()]),
  );
}

const light = block(':root');
const dark = { ...light, ...block(":root[data-theme='dark']") };

function resolve(tokens: Record<string, string>, name: string): string {
  const value = tokens[name] ?? '';
  const ref = /^var\((--[\w-]+)\)$/.exec(value);
  return ref ? resolve(tokens, ref[1]!) : value;
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
}

describe.each([
  ['light', light],
  ['dark', dark],
])('radar dot colours, %s theme', (_, tokens) => {
  // Live and index: accent (filled and ring); slow or limited; failed; skipped or not checked.
  it.each(['--accent', '--status-warn-ink', '--danger', '--muted'])(
    '%s is at least 3:1 on the page',
    (token) => {
      const colour = resolve(tokens, token);
      expect(colour).toMatch(/^#[0-9a-f]{6}$/i);
      expect(contrast(colour, resolve(tokens, '--bg'))).toBeGreaterThanOrEqual(3);
    },
  );
});
