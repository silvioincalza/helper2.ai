import { renderHook } from '@testing-library/react';
import { useMediaQuery } from './useMediaQuery';

describe('useMediaQuery', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('listens once while the query stays the same, however often the page renders', () => {
    const add = vi.fn();
    vi.stubGlobal(
      'matchMedia',
      vi.fn((query: string) => ({
        matches: true,
        media: query,
        addEventListener: add,
        removeEventListener: () => undefined,
      })),
    );
    const { result, rerender } = renderHook(({ q }) => useMediaQuery(q, false), {
      initialProps: { q: '(max-width: 720px)' },
    });

    rerender({ q: '(max-width: 720px)' });
    rerender({ q: '(max-width: 720px)' });

    expect(result.current).toBe(true);
    expect(add).toHaveBeenCalledTimes(1);
  });
});
