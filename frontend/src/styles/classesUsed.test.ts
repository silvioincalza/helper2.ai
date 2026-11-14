import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const WRITTEN_BY_THE_BACKEND = ['h2-prerender'];

const root = process.cwd();
const src = join(root, 'src');

function files(dir: string, ext: RegExp): string[] {
  return readdirSync(dir, { withFileTypes: true, recursive: true })
    .filter((e) => e.isFile() && ext.test(e.name) && !/\.test\.tsx?$/.test(e.name))
    .map((e) => join(e.parentPath, e.name));
}

const code = [
  ...files(src, /\.tsx?$/).filter(
    (f) => !f.startsWith(join(src, 'api')) && !f.startsWith(join(src, 'test')),
  ),
  ...files(join(root, 'public'), /\.(js|html)$/),
  join(root, 'index.html'),
  join(root, 'admin.html'),
]
  .map((f) => readFileSync(f, 'utf8'))
  .join('\n');

const dynamic = [...code.matchAll(/[\s`'"]([a-z][\w-]*[-_])\$\{/g)].map((m) => m[1]!);

function classes(css: string): string[] {
  const selectors = [...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{/g)]
    .map((m) => m[1]!)
    .filter((s) => !s.trim().startsWith('@'))
    .join(' ');
  return [...new Set([...selectors.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map((m) => m[1]!))];
}

const written = (name: string) =>
  new RegExp(`(?<![\\w-])${name}(?![\\w-])`).test(code) || dynamic.some((p) => name.startsWith(p));

describe('the style sheets', () => {
  it.each(files(src, /\.css$/).map((f) => [f.slice(src.length + 1), f] as const))(
    '%s names no class the code never writes',
    (_, file) => {
      const dead = classes(readFileSync(file, 'utf8')).filter(
        (c) => !WRITTEN_BY_THE_BACKEND.includes(c) && !written(c),
      );
      expect(dead).toEqual([]);
    },
  );
});
