import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import en from './locales/en.json';
import enAdmin from './locales/en.admin.json';
import enLegal from './locales/en.legal.json';

const src = join(process.cwd(), 'src');
const files = readdirSync(src, { withFileTypes: true, recursive: true })
  .filter((e) => e.isFile() && /\.tsx?$/.test(e.name) && !/\.test\.tsx?$/.test(e.name))
  .map((e) => join(e.parentPath, e.name))
  .filter((f) => !f.startsWith(join(src, 'api')) && !f.startsWith(join(src, 'test')));
const read = (list: string[]) => list.map((f) => readFileSync(f, 'utf8')).join('\n');
const code = read(files);
const publicCode = read(files.filter((f) => !f.startsWith(join(src, 'admin'))));

/** Every leaf key of [tree], as `a.b.c`. A list (read with returnObjects) is one leaf. */
function leaves(tree: object, prefix = ''): string[] {
  return Object.entries(tree).flatMap(([k, v]) =>
    v && typeof v === 'object' && !Array.isArray(v)
      ? leaves(v as object, `${prefix}${k}.`)
      : [`${prefix}${k}`],
  );
}

/** The prefixes the code completes at run time: `a.b.${x}` gives `a.b.`. */
const dynamicIn = (text: string) => [...text.matchAll(/`([\w.]+\.)\$\{/g)].map((m) => m[1]!);

const PLURAL = /_(zero|one|two|few|many|other)$/;
const quotedIn = (text: string, key: string) =>
  new RegExp(`['"\`]${key.replace(/\./g, '\\.')}['"\`]`).test(text);

function unused(tree: object, text: string): string[] {
  const dynamic = dynamicIn(text);
  return leaves(tree).filter((key) => {
    const base = key.replace(PLURAL, '');
    return !quotedIn(text, base) && !dynamic.some((p) => base.startsWith(p));
  });
}

describe('the translations', () => {
  it('of the public app have no key only the back office uses', () => {
    expect(unused(en, publicCode)).toEqual([]);
    expect(unused(enLegal, publicCode)).toEqual([]);
  });

  it('of the back office have no unused key', () => {
    expect(unused(enAdmin, code)).toEqual([]);
  });
});
