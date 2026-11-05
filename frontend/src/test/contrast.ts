import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The colour tokens of tokens.css as the browser resolves them, per theme, and the WCAG contrast ratio of two of
 * them. For the contrast tests: jsdom cannot compute colours, so axe leaves contrast out.
 */
const css = readFileSync(join(process.cwd(), 'src/styles/tokens.css'), 'utf8');

/** The custom properties declared in the first block that starts with `selector {`. */
function block(selector: string): Record<string, string> {
  const start = css.indexOf(`${selector} {`);
  const body = css.slice(start, css.indexOf('}', start));
  return Object.fromEntries(
    [...body.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map((m) => [m[1]!, m[2]!.trim()]),
  );
}

const light = block(':root');
export const THEMES = {
  light,
  dark: { ...light, ...block(":root[data-theme='dark']") },
};
export type Tokens = Record<string, string>;

/** A token's colour, following var() references. */
function resolve(tokens: Tokens, name: string): string {
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

/** The contrast ratio of two #rrggbb colours. */
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
}

/** The contrast ratio of two tokens in one theme. */
export function tokenContrast(tokens: Tokens, a: string, b: string): number {
  const [x, y] = [resolve(tokens, a), resolve(tokens, b)];
  expect(x).toMatch(/^#[0-9a-f]{6}$/i);
  expect(y).toMatch(/^#[0-9a-f]{6}$/i);
  return contrast(x, y);
}
