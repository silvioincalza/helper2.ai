import { act, renderHook, waitFor } from '@testing-library/react';
import { CACHE_MAX, CACHE_TTL_MS, useApi } from './useApi';

function fetcher(value: string) {
  return vi.fn((_signal: AbortSignal) => Promise.resolve(value));
}

describe('useApi recent-answers cache', () => {
  it('a second mount with the same key shows the answer at once, without a request', async () => {
    const f = fetcher('first');
    const a = renderHook(() => useApi('k', f));
    await waitFor(() => expect(a.result.current.data).toBe('first'));
    a.unmount();

    const g = fetcher('second');
    const b = renderHook(() => useApi('k', g));
    expect(b.result.current).toMatchObject({ data: 'first', loading: false });
    expect(g).not.toHaveBeenCalled();
  });

  it('reload always asks again and keeps the new answer', async () => {
    const f = vi.fn().mockResolvedValueOnce('old').mockResolvedValueOnce('new');
    const a = renderHook(() => useApi('k', f));
    await waitFor(() => expect(a.result.current.data).toBe('old'));
    act(() => a.result.current.reload());
    await waitFor(() => expect(a.result.current.data).toBe('new'));
    expect(f).toHaveBeenCalledTimes(2);
    a.unmount();

    const b = renderHook(() => useApi('k', fetcher('unused')));
    expect(b.result.current.data).toBe('new');
  });

  it('cache: false always asks', async () => {
    const f = fetcher('x');
    renderHook(() => useApi('k', f, { cache: false })).unmount();
    await waitFor(() => expect(f).toHaveBeenCalledTimes(1));
    const b = renderHook(() => useApi('k', f, { cache: false }));
    expect(b.result.current.loading).toBe(true);
    await waitFor(() => expect(f).toHaveBeenCalledTimes(2));
  });

  it('an answer older than the time limit is asked again', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000);
    const a = renderHook(() => useApi('k', fetcher('old')));
    await waitFor(() => expect(a.result.current.data).toBe('old'));
    a.unmount();

    now.mockReturnValue(1_000 + CACHE_TTL_MS + 1);
    const g = fetcher('fresh');
    const b = renderHook(() => useApi('k', g));
    expect(b.result.current.loading).toBe(true);
    await waitFor(() => expect(b.result.current.data).toBe('fresh'));
  });

  it('an answer shown from the cache stays shown after its cache entry expires', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000);
    const a = renderHook(() => useApi('k', fetcher('cached')));
    await waitFor(() => expect(a.result.current.data).toBe('cached'));
    a.unmount();

    const g = fetcher('unused');
    const b = renderHook(() => useApi('k', g));
    expect(b.result.current.data).toBe('cached');
    now.mockReturnValue(1_000 + CACHE_TTL_MS + 1);
    b.rerender();
    expect(b.result.current).toMatchObject({ data: 'cached', loading: false });
    expect(g).not.toHaveBeenCalled();
    now.mockRestore();
  });

  it('keeps only the most recent answers', async () => {
    for (let i = 0; i <= CACHE_MAX; i++) {
      const h = renderHook(() => useApi(`k${i}`, fetcher(`v${i}`)));
      await waitFor(() => expect(h.result.current.data).toBe(`v${i}`));
      h.unmount();
    }
    const oldest = fetcher('again');
    renderHook(() => useApi('k0', oldest));
    await waitFor(() => expect(oldest).toHaveBeenCalledTimes(1));
    const newest = fetcher('unused');
    expect(renderHook(() => useApi(`k${CACHE_MAX}`, newest)).result.current.data).toBe(`v${CACHE_MAX}`);
    expect(newest).not.toHaveBeenCalled();
  });

  it('errors are not kept', async () => {
    const f = vi.fn((_s: AbortSignal) => Promise.reject(new Error('down')));
    const a = renderHook(() => useApi('k', f));
    await waitFor(() => expect(a.result.current.error).toBeInstanceOf(Error));
    a.unmount();
    const g = fetcher('up');
    const b = renderHook(() => useApi('k', g));
    await waitFor(() => expect(b.result.current.data).toBe('up'));
    expect(g).toHaveBeenCalledTimes(1);
  });
});
