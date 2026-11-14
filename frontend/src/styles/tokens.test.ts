import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { BREAKPOINTS, HEIGHTS } from '@/lib/breakpoints';

interface Token {
  name: string;
  value: string | { light: string; dark: string };
  alias?: string;
}
interface Tokens {
  color: { tokens: Token[] };
  type: { scale: { tokens: Token[] }; styles: { tokens: Token[] } };
  spacing: { tokens: Token[] };
  radius: { tokens: Token[] };
  size: { tokens: Token[] };
  motion: { tokens: { name: string; value: string }[] };
  elevation: { tokens: Token[] };
  layers: { tokens: Token[] };
  breakpoints: {
    tokens: { name: string; value: string }[];
    heights: { name: string; value: string }[];
  };
}

const tokens = JSON.parse(
  readFileSync(join(process.cwd(), '../docs/design/system/tokens.json'), 'utf8'),
) as Tokens;
const src = join(process.cwd(), 'src');
const css = readFileSync(join(src, 'styles/tokens.css'), 'utf8');

function block(selector: string): Map<string, string> {
  const start = css.indexOf(`${selector} {`);
  const body = css.slice(start, css.indexOf('\n}', start)).replace(/\/\*[\s\S]*?\*\//g, '');
  return new Map(
    [...body.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map(([, name, value]) => [
      name!,
      value!.replace(/\s+/g, ' ').trim(),
    ]),
  );
}

const light = block(':root');
const dark = block(":root[data-theme='dark']");
const colors = tokens.color.tokens as Token[];
const textSteps = tokens.type.scale.tokens;
const others = [
  ...textSteps,
  ...tokens.type.styles.tokens,
  ...tokens.spacing.tokens,
  ...tokens.radius.tokens,
  ...tokens.size.tokens,
  ...tokens.motion.tokens.filter((t) => /^\d+ms$/.test(t.value)),
  ...tokens.elevation.tokens,
  ...tokens.layers.tokens,
] as Token[];

const same = (value: string) =>
  value
    .replace(/^([\d.]+)rem$/, (_, n: string) => `${Number(n) * 16}px`)
    .replace(/\s/g, '')
    .replace(/(?<!\d)0\./g, '.');

function files(ext: RegExp): string[] {
  return readdirSync(src, { withFileTypes: true, recursive: true })
    .filter((e) => e.isFile() && ext.test(e.name) && !/\.test\.tsx?$/.test(e.name))
    .map((e) => join(e.parentPath, e.name))
    .filter((f) => !f.startsWith(join(src, 'api')) && !f.startsWith(join(src, 'test')));
}
const rel = (f: string) => f.slice(src.length + 1);
const publicCss = files(/\.css$/).filter(
  (f) => !f.startsWith(join(src, 'admin')) && !f.endsWith(join('styles', 'tokens.css')),
);
const declarations = (property: string) =>
  publicCss.flatMap((f) =>
    [
      ...readFileSync(f, 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .matchAll(new RegExp(`(?<![\\w-])${property}:\\s*([^;}]+)`, 'g')),
    ].map((m) => ({ file: rel(f), value: m[1]!.replace(/\s+/g, ' ').trim() })),
  );
const literal = (property: string, allowed: RegExp) =>
  declarations(property)
    .filter((d) => !allowed.test(d.value))
    .map((d) => `${d.file}: ${property}: ${d.value}`);

describe('the colour tokens', () => {
  it.each(colors.map((t) => [t.name, t] as const))(
    '--%s has the value of tokens.json in both themes',
    (name, t) => {
      const value = t.value as { light: string; dark: string };
      if (t.alias) {
        expect(light.get(`--${name}`)).toBe(`var(--${t.alias})`);
        expect(dark.has(`--${name}`)).toBe(false);
        return;
      }
      expect(light.get(`--${name}`)).toBe(value.light);
      expect(dark.get(`--${name}`)).toBe(value.dark);
    },
  );

  it('are all in tokens.json: tokens.css gives no other variable a colour', () => {
    const named = new Set(colors.map((t) => `--${t.name}`));
    const colourVars = [...light, ...dark]
      .filter(([, v]) => /^(#|rgba?\()/.test(v) || /^var\(--(?!radius)/.test(v))
      .map(([n]) => n);
    expect(colourVars.filter((n) => !named.has(n))).toEqual([]);
  });

  it('are written nowhere else: no hex colour outside tokens.css', () => {
    const outside = files(/\.(css|tsx?)$/)
      .filter((f) => !f.endsWith(join('styles', 'tokens.css')))
      .flatMap((f) =>
        [
          ...readFileSync(f, 'utf8').matchAll(
            /(?<![\w&])#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![\w-])/g,
          ),
        ].map((m) => `${rel(f)}: ${m[0]}`),
      )
      .filter((hit) => hit.includes('.css:') || /#\w*[a-fA-F]/.test(hit));
    expect(outside).toEqual([]);
  });
});

describe('the other tokens', () => {
  it.each(others.map((t) => [t.name, t] as const))(
    '--%s has the value of tokens.json',
    (name, t) => {
      const value = light.get(`--${name}`);
      expect(value, `--${name} is missing in tokens.css`).toBeDefined();
      if (typeof t.value === 'string') {
        expect(same(value!)).toBe(same(t.value));
        expect(dark.has(`--${name}`)).toBe(false);
        return;
      }
      expect(same(value!)).toBe(same(t.value.light));
      expect(same(dark.get(`--${name}`) ?? '')).toBe(same(t.value.dark));
    },
  );
});

describe('the radius and the shadows', () => {
  it('are the only corner sizes in the public CSS: a token, a circle (50%) or none (0)', () => {
    expect(
      declarations('border(?:-[a-z]+){0,2}-radius').filter((d) => /\d(px|r?em)/.test(d.value)),
    ).toEqual([]);
  });

  it('are the only colours with an alpha: no rgb() or rgba() outside tokens.css', () => {
    const outside = publicCss.filter((f) => /rgba?\(/.test(readFileSync(f, 'utf8'))).map(rel);
    expect(outside).toEqual([]);
  });

  it('have a dark value each: ink is lighter than the dark page, so an ink shadow cannot be seen', () => {
    const noDark = tokens.elevation.tokens.filter((t) => typeof t.value === 'string');
    expect(noDark.map((t) => t.name)).toEqual([]);
  });

  it('use one of four weights: 400, 500, 600, 700', () => {
    expect(literal('font-weight', /^(400|500|600|700|inherit|normal)$/)).toEqual([]);
  });
});

describe('the type scale', () => {
  const FLOOR_REM = 0.75;
  const smallest = (value: string) =>
    Math.min(...[...value.matchAll(/(?<![\w.+-])([\d.]+)rem/g)].map((m) => Number(m[1])));

  it('is in rem, so text follows the font size the user set in the browser', () => {
    expect(textSteps.filter((t) => /\dpx/.test(String(t.value))).map((t) => t.name)).toEqual([]);
  });

  it('has no step under 12px', () => {
    const fixed = textSteps.filter((t) => /^[\d.]+rem$/.test(String(t.value)));
    const fluid = textSteps.filter((t) => String(t.value).startsWith('clamp('));
    expect(fixed.length + fluid.length).toBe(textSteps.length);
    expect(fixed.filter((t) => Number.parseFloat(String(t.value)) < FLOOR_REM)).toEqual([]);
    expect(fluid.filter((t) => smallest(String(t.value)) < FLOOR_REM)).toEqual([]);
  });

  it('is the only font size in the public CSS: a step, the parent size with the floor, or inherit', () => {
    expect(
      literal('font-size', /^(var\(--text-[\w-]+\)|inherit|max\([\d.]+em, var\(--text-12\)\))$/),
    ).toEqual([]);
    expect(
      literal(
        'font',
        /^(inherit|var\(--type-[\w-]+\)|\d{3} var\(--text-[\w-]+\) \/ ([\d.]+(rem)?|var\(--[\w-]+\)) var\(--font-\w+\))$/,
      ),
    ).toEqual([]);
  });

  it('names only steps that exist', () => {
    const known = new Set([...textSteps, ...tokens.type.styles.tokens].map((t) => `--${t.name}`));
    const used = publicCss.flatMap((f) =>
      [...readFileSync(f, 'utf8').matchAll(/var\((--(?:text|type)-[\w-]+)\)/g)].map((m) => m[1]!),
    );
    expect([...new Set(used)].filter((name) => !known.has(name))).toEqual([]);
  });
});

describe('the layers', () => {
  const layers = tokens.layers.tokens.map((t) => Number(t.value));

  it('are listed from the lowest to the highest', () => {
    expect(layers).toEqual([...layers].sort((a, b) => a - b));
  });

  it('are the only z-index in the public CSS', () => {
    expect(literal('z-index', /^(var\(--z-[\w-]+\)|auto)$/)).toEqual([]);
  });

  it('are named only by a token that exists', () => {
    const known = new Set(tokens.layers.tokens.map((t) => `--${t.name}`));
    const used = declarations('z-index').map((d) => /var\((--z-[\w-]+)\)/.exec(d.value)?.[1]);
    expect(used.filter((name) => name && !known.has(name))).toEqual([]);
  });
});

describe('the breakpoints', () => {
  const widths = tokens.breakpoints.tokens.map((t) => Number.parseInt(t.value, 10));

  it('are the same in tokens.json and lib/breakpoints.ts', () => {
    expect(Object.values(BREAKPOINTS)).toEqual(widths);
  });

  it('are the only widths in a CSS media query: up to W, above W (W + 1) or from W', () => {
    const allowed = new Set(widths.flatMap((w) => [`max-${w}`, `min-${w + 1}`, `min-${w}`]));
    const used = files(/\.css$/).flatMap((f) =>
      [...readFileSync(f, 'utf8').matchAll(/\((max|min)-width:\s*(\d+)px\)/g)].map(
        ([, kind, w]) => `${kind}-${w} in ${rel(f)}`,
      ),
    );
    expect(used.filter((q) => !allowed.has(q.split(' ')[0]!))).toEqual([]);
  });

  it('are never numbers in a JavaScript query: they come from lib/breakpoints.ts', () => {
    const literal = files(/\.tsx?$/)
      .filter((f) => !f.endsWith(join('lib', 'breakpoints.ts')))
      .filter((f) => /\((max|min)-(width|height):\s*\d/.test(readFileSync(f, 'utf8')));
    expect(literal.map(rel)).toEqual([]);
  });

  const heights = tokens.breakpoints.heights.map((t) => Number.parseInt(t.value, 10));

  it('have the same heights in tokens.json and lib/breakpoints.ts', () => {
    expect(Object.values(HEIGHTS)).toEqual(heights);
  });

  it('are the only heights in a CSS media query: up to H', () => {
    const allowed = new Set(heights.map((h) => `max-${h}`));
    const used = files(/\.css$/).flatMap((f) =>
      [...readFileSync(f, 'utf8').matchAll(/\((max|min)-height:\s*([\d.]+)(px|r?em)\)/g)].map(
        ([, kind, h, unit]) => `${kind}-${h}${unit === 'px' ? '' : unit} in ${rel(f)}`,
      ),
    );
    expect(used.filter((q) => !allowed.has(q.split(' ')[0]!))).toEqual([]);
  });
});
