import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * A box that scrolls sideways (a wide table on a phone) must also be the containing block of what is positioned
 * inside it. Otherwise an absolutely positioned child (the sr-only text of a link) is placed against the page, escapes
 * the box's clip and makes the page wider than the screen. jsdom cannot measure layout, so this checks the rule.
 */
function cssFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory()
      ? cssFiles(join(dir, e.name))
      : e.name.endsWith('.css')
        ? [join(dir, e.name)]
        : [],
  );
}

const rules = cssFiles(join(process.cwd(), 'src')).flatMap((file) =>
  [...readFileSync(file, 'utf8').matchAll(/([^{}]+)\{([^}]*)\}/g)].map((m) => ({
    file,
    selector: m[1]!.replace(/\/\*[\s\S]*?\*\//g, '').trim(),
    body: m[2]!,
  })),
);
const scrollers = rules.filter((r) => /overflow(-x)?:\s*auto/.test(r.body));

it('finds the scroll boxes', () => {
  expect(scrollers.map((r) => r.selector)).toContain('.v4-scroll');
});

it.each(scrollers.map((r) => [r.selector, r.body] as const))(
  '%s is the containing block of its positioned children',
  (_selector, body) => {
    expect(body).toMatch(/position:\s*(relative|absolute|fixed|sticky)/);
  },
);
