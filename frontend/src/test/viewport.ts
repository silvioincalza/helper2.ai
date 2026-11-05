import { vi } from 'vitest';

/**
 * A window [width] px wide for `matchMedia` (jsdom has none): answers `(max-width: N px)` and `(min-width: N px)`, and
 * nothing else. Without it the components take their wide-screen fallback.
 */
export function mockViewport(width: number) {
  const matches = (query: string) =>
    [...query.matchAll(/\((max|min)-width:\s*(\d+)px\)/g)].every(([, kind, px]) =>
      kind === 'max' ? width <= Number(px) : width >= Number(px),
    ) && /width/.test(query);
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: matches(query),
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    })),
  );
}
