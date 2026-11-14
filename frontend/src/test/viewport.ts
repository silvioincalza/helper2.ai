import { vi } from 'vitest';

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
