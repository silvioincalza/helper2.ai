import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { HEIGHTS } from '@/lib/breakpoints';

const src = join(process.cwd(), 'src');
const sheets = readdirSync(src, { withFileTypes: true, recursive: true })
  .filter((e) => e.isFile() && e.name.endsWith('.css'))
  .map((e) => readFileSync(join(e.parentPath, e.name), 'utf8').replace(/\/\*[\s\S]*?\*\//g, ''));

interface Rule {
  selector: string;
  body: string;
}

const rulesIn = (css: string): Rule[] =>
  [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((m) => ({
    selector: m[1]!.trim().replace(/\s+/g, ' '),
    body: m[2]!,
  }));
const rules = sheets.flatMap(rulesIn);
const declared = (selector: string) =>
  rules
    .filter((r) => r.selector.split(/,\s*/).includes(selector))
    .map((r) => r.body)
    .join('\n');

const SHORT = `@media (max-height: ${HEIGHTS.short}px)`;
const shortRules = sheets.flatMap((css) =>
  css
    .split(SHORT)
    .slice(1)
    .flatMap((rest) => rulesIn(rest.slice(rest.indexOf('{') + 1, rest.indexOf('\n}')))),
);

describe('Feature: keyboard focus is never hidden (WCAG 2.4.7, 2.4.11)', () => {
  it('Scenario: the page keeps a focused item clear of what sticks to the top and to the bottom', () => {
    const html = declared('html');
    expect(html).toMatch(
      /scroll-padding-top:\s*calc\(var\(--sticky-top, 0px\) \+ var\(--space-3\)\)/,
    );
    expect(html).toMatch(
      /scroll-padding-bottom:\s*calc\(var\(--sticky-bottom, 0px\) \+ var\(--space-3\)\)/,
    );
    expect(declared('body')).toMatch(/padding-bottom:\s*var\(--sticky-bottom, 0px\)/);
  });

  it('Scenario: the focus ring keeps the shape of its control: no rule changes the radius on focus', () => {
    const reshaped = rules.filter(
      (r) =>
        /:focus(-visible|-within)?(?![\w-]|::)/.test(r.selector) && /border-radius/.test(r.body),
    );
    expect(reshaped.map((r) => r.selector)).toEqual([]);
  });

  it('Scenario: List and Timeline sit in a box that clips, so their ring is drawn inside, in a colour that shows on the selected one', () => {
    expect(declared('.h2-view')).toMatch(/overflow:\s*hidden/);
    expect(declared('.h2-results .h2-view a:focus-visible')).toMatch(/outline-offset:\s*-3px/);
    const selected = declared(".h2-results .h2-view a[aria-current='page']:focus-visible");
    expect(selected).toMatch(/outline-color:\s*var\(--on-selected\)/);
    expect(selected).toMatch(/outline-offset:\s*-6px/);
  });

  it('Scenario: the home lists clip only up and down, where the rows slide, so a ring is whole at the sides', () => {
    const ticker = declared('.h2-ticker');
    expect(ticker).toMatch(/overflow-x:\s*visible/);
    expect(ticker).toMatch(/overflow-y:\s*clip/);
    expect(ticker).not.toMatch(/overflow:\s/);
  });

  it('Scenario: the examples and the legend fold away in a box that clips, with room for a ring on every side', () => {
    const more = declared('.h2-pinned__more');
    expect(more).toMatch(/overflow:\s*hidden/);
    expect(more).toMatch(/padding:\s*var\(--space-2\)/);
    expect(more).toMatch(/margin:\s*calc\(-1 \* var\(--space-2\)\)/);
  });

  it('Scenario: the chips on the results page scroll in a row that clips, with room for a ring on every side, and a focused chip stops clear of the faded edge', () => {
    const row = declared('.h2-results .h2-hints');
    expect(row).toMatch(/overflow-x:\s*auto/);
    expect(row).toMatch(/padding:\s*var\(--space-2\)/);
    expect(row).toMatch(/margin:\s*calc\(-1 \* var\(--space-2\)\)/);
    expect(row).toMatch(/scroll-padding-inline:\s*var\(--space-6\)/);
    expect(declared('.h2-results .h2-hints[data-more-end]')).toMatch(
      /--more-end:\s*var\(--space-5\)/,
    );
    expect(declared('.h2-results .h2-hints[data-more-start]')).toMatch(
      /--more-start:\s*var\(--space-5\)/,
    );
  });

  it('Scenario: the two rows of chips stack in one column that cannot grow wider than the page (WCAG 1.4.10)', () => {
    expect(declared('.h2-hints-row')).toMatch(/flex-flow:\s*column nowrap/);
  });

  it('Scenario: a radar dot under the focus or the mouse shows in full, also between two passes of the beam', () => {
    expect(declared('.h2-radar__dots .h2-dot:focus-visible')).toMatch(/opacity:\s*1\b/);
  });

  it.each(['.h2-app.site-header', '.h2-pinned', '.h2-home .h2-tabs__list', '.h2-consent-slot'])(
    'Scenario: on a short screen %s does not stick, so it cannot cover half the page (WCAG 1.4.10)',
    (selector) => {
      expect(declared(selector)).toMatch(/position:\s*(sticky|fixed)/);
      const unstuck = shortRules
        .filter((r) => r.selector.split(/,\s*/).includes(selector))
        .map((r) => r.body)
        .join('\n');
      expect(unstuck).toMatch(/position:\s*static/);
    },
  );
});
