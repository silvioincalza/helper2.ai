import { act, renderHook, waitFor } from '@testing-library/react';
import { API_CACHE_SIZE, API_FRESH_MS, useApi } from './useApi';

/** A fetcher that answers `<key>:<n>` with a call counter per key. */
function counting() {
  const calls: Record<string, number> = {};
  const fetcher = (key: string) => () => {
    calls[key] = (calls[key] ?? 0) + 1;
    return Promise.resolve(`${key}:${calls[key]}`);
  };
  return { calls, fetcher };
}

function mount(key: string, fetcher: (key: string) => () => Promise<string>) {
  return renderHook(({ k }) => useApi(k, fetcher(k)), { initialProps: { k: key } });
}

describe('useApi response cache', () => {
  it('a key seen moments ago renders its response at once, without a request', async () => {
    const { calls, fetcher } = counting();
    const first = mount('search?q=a', fetcher);
    await waitFor(() => expect(first.result.current.data).toBe('search?q=a:1'));
    first.unmount();

    const again = mount('search?q=a', fetcher);
    expect(again.result.current).toMatchObject({ data: 'search?q=a:1', loading: false });
    await act(() => Promise.resolve());
    expect(calls['search?q=a']).toBe(1);
  });

  it('an older response renders at once and is replaced by a fresh one in the background', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000_000);
    const { calls, fetcher } = counting();
    const first = mount('resource/1', fetcher);
    await waitFor(() => expect(first.result.current.data).toBe('resource/1:1'));
    first.unmount();

    now.mockReturnValue(1_000_000 + API_FRESH_MS + 1);
    const again = mount('resource/1', fetcher);
    expect(again.result.current).toMatchObject({ data: 'resource/1:1', loading: false });
    await waitFor(() => expect(again.result.current.data).toBe('resource/1:2'));
    expect(calls['resource/1']).toBe(2);
  });

  it('keeps the older response on screen when the background request fails', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000_000);
    let fail = false;
    const fetcher = () => () => (fail ? Promise.reject(new Error('down')) : Promise.resolve('ok'));
    const first = mount('k', fetcher);
    await waitFor(() => expect(first.result.current.data).toBe('ok'));
    first.unmount();

    now.mockReturnValue(1_000_000 + API_FRESH_MS + 1);
    fail = true;
    const again = mount('k', fetcher);
    await act(() => Promise.resolve());
    expect(again.result.current).toMatchObject({ data: 'ok', error: undefined, loading: false });
  });

  it('reload asks the server again instead of reusing the cached response', async () => {
    const { calls, fetcher } = counting();
    const view = mount('runs', fetcher);
    await waitFor(() => expect(view.result.current.data).toBe('runs:1'));
    act(() => view.result.current.reload());
    expect(view.result.current.loading).toBe(true);
    await waitFor(() => expect(view.result.current.data).toBe('runs:2'));
    expect(calls['runs']).toBe(2);
  });

  it(`keeps the ${API_CACHE_SIZE} most recently used responses and drops the oldest`, async () => {
    const { calls, fetcher } = counting();
    const view = mount('k0', fetcher);
    await waitFor(() => expect(view.result.current.data).toBe('k0:1'));
    for (let i = 1; i <= API_CACHE_SIZE; i++) {
      view.rerender({ k: `k${i}` });
      await waitFor(() => expect(view.result.current.data).toBe(`k${i}:1`));
    }
    view.rerender({ k: 'k1' });
    expect(view.result.current.data).toBe('k1:1');
    view.rerender({ k: 'k0' });
    expect(view.result.current.loading).toBe(true);
    await waitFor(() => expect(view.result.current.data).toBe('k0:2'));
    expect(calls['k1']).toBe(1);
  });
});
