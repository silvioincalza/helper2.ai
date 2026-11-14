import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const src = join(process.cwd(), 'src');
const sheets = readdirSync(src, { withFileTypes: true, recursive: true })
  .filter((e) => e.isFile() && e.name.endsWith('.css'))
  .map((e) => join(e.parentPath, e.name))
  .filter((f) => !f.startsWith(join(src, 'admin')))
  .map((f) => [f.slice(src.length + 1), f] as const);

interface Block {
  prelude: string;
  body: string;
}

function topLevelBlocks(css: string): Block[] {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const blocks: Block[] = [];
  let depth = 0;
  let prelude = '';
  let body = '';
  for (const ch of text) {
    if (ch === '{') {
      depth += 1;
      if (depth === 1) continue;
    }
    if (ch === '}') {
      depth -= 1;
      if (depth === 0) {
        blocks.push({ prelude: prelude.replace(/\s+/g, ' ').trim(), body: body.trim() });
        prelude = '';
        body = '';
        continue;
      }
    }
    if (depth === 0) prelude += ch;
    else body += ch;
  }
  return blocks;
}

describe('Feature: one rule per selector', () => {
  it.each(sheets)('Scenario: %s declares each top-level selector once', (_, file) => {
    const selectors = topLevelBlocks(readFileSync(file, 'utf8'))
      .map((b) => b.prelude)
      .filter((p) => !p.startsWith('@'));
    const twice = selectors.filter((s, i) => selectors.indexOf(s) !== i);
    expect([...new Set(twice)]).toEqual([]);
  });

  it.each(sheets)('Scenario: %s has no empty at-rule', (_, file) => {
    const empty = topLevelBlocks(readFileSync(file, 'utf8'))
      .filter((b) => b.prelude.startsWith('@') && b.body === '')
      .map((b) => b.prelude);
    expect(empty).toEqual([]);
  });
});
