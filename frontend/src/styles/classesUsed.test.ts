import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ANY_TEXT, templatesIn } from '../test/templates';

const WRITTEN_BY_THE_BACKEND = ['h2-prerender'];

const root = process.cwd();
const src = join(root, 'src');

function files(dir: string, ext: RegExp): string[] {
  return readdirSync(dir, { withFileTypes: true, recursive: true })
    .filter((e) => e.isFile() && ext.test(e.name) && !/\.test\.tsx?$/.test(e.name))
    .map((e) => join(e.parentPath, e.name));
}

const scripts = files(src, /\.tsx?$/).filter(
  (f) => !f.startsWith(join(src, 'api')) && !f.startsWith(join(src, 'test')),
);

const code = [
  ...scripts,
  ...files(join(root, 'public'), /\.(js|html)$/),
  join(root, 'index.html'),
  join(root, 'admin.html'),
]
  .map((f) => readFileSync(f, 'utf8'))
  .join('\n');

const fromTemplates = new Set(
  templatesIn(root, scripts)
    .flatMap((t) => t.texts)
    .flatMap((text) => text.split(/\s+/))
    .filter((word) => !word.includes(ANY_TEXT)),
);

function classes(css: string): string[] {
  const selectors = [...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{/g)]
    .map((m) => m[1]!)
    .filter((s) => !s.trim().startsWith('@'))
    .join(' ');
  return [...new Set([...selectors.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map((m) => m[1]!))];
}

const written = (name: string) =>
  new RegExp(`(?<![\\w-])${name}(?![\\w-])`).test(code) || fromTemplates.has(name);

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
