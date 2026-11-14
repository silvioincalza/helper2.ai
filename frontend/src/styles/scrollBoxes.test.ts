import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

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
  expect(scrollers.map((r) => r.selector)).toContain('.h2-scroll');
});

it.each(scrollers.map((r) => [r.selector, r.body] as const))(
  '%s is the containing block of its positioned children',
  (_selector, body) => {
    expect(body).toMatch(/position:\s*(relative|absolute|fixed|sticky)/);
  },
);
