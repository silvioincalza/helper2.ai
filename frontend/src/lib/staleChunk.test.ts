import { CHUNK_RELOAD_KEY, RELOAD_GUARD_MS, followNavigation, loadChunk } from '@/lib/staleChunk';

type Listener = (state: {
  navigation: { location?: { pathname: string; search: string; hash: string } };
}) => void;

describe('loading a page file', () => {
  const reload = vi.fn();
  const assign = vi.fn();
  const settled = vi.fn();
  let listener: Listener = () => {};
  let unfollow = () => {};

  beforeEach(() => {
    window.sessionStorage.clear();
    reload.mockReset();
    assign.mockReset();
    settled.mockReset();
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      pathname: '/saved',
      search: '',
      hash: '',
      reload,
      assign,
    });
    unfollow = followNavigation({
      subscribe: (l: Listener) => {
        listener = l;
        return () => {
          listener = () => {};
        };
      },
    });
    listener({ navigation: {} });
  });

  afterEach(() => {
    unfollow();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  const missingFile = () =>
    Promise.reject(
      new TypeError('Failed to fetch dynamically imported module: /assets/SavedPage-old.js'),
    );
  const pageFile = () => Promise.resolve({ default: 'page' });
  const tick = () => new Promise((r) => setTimeout(r, 0));
  const reloaded = (address: string) =>
    window.sessionStorage.setItem(CHUNK_RELOAD_KEY, JSON.stringify({ address, at: Date.now() }));

  it('returns the module', async () => {
    await expect(loadChunk(pageFile)).resolves.toEqual({ default: 'page' });
    expect(reload).not.toHaveBeenCalled();
  });

  it('reloads the tab once when the file of an older release is gone, and keeps the old page meanwhile', async () => {
    loadChunk(missingFile).then(settled, settled);
    await tick();
    expect(reload).toHaveBeenCalledTimes(1);
    expect(JSON.parse(window.sessionStorage.getItem(CHUNK_RELOAD_KEY) ?? '{}')).toMatchObject({
      address: '/saved',
    });
    expect(settled).not.toHaveBeenCalled();
  });

  it('does not reload a second time for the same address: the error goes on', async () => {
    reloaded('/saved');
    await expect(loadChunk(missingFile)).rejects.toThrow(
      'Failed to fetch dynamically imported module',
    );
    expect(reload).not.toHaveBeenCalled();
  });

  it('a route with two files, one that loads and one that keeps failing, reloads the tab only once', async () => {
    // The admin layout loads, its page does not: before, the layout cleared the guard and the tab reloaded forever.
    for (let visit = 0; visit < 3; visit++) {
      await loadChunk(pageFile);
      loadChunk(missingFile).then(settled, settled);
      await tick();
    }
    expect(reload).toHaveBeenCalledTimes(1);
    expect(settled).toHaveBeenCalledTimes(2);
  });

  it('reloads the same address again once the guard has passed, for a later release', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    reloaded('/saved');
    vi.setSystemTime(Date.now() + RELOAD_GUARD_MS + 1);
    loadChunk(missingFile).then(settled, settled);
    await tick();
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('in the page the reload opened, a file that fails after the guard has passed does not reload again', async () => {
    // A proxy times the file out after more than 30 s: the reloaded page must still not reload a second time.
    vi.useFakeTimers({ toFake: ['Date'] });
    window.sessionStorage.setItem(
      CHUNK_RELOAD_KEY,
      JSON.stringify({ address: '/saved', at: performance.timeOrigin - 1 }),
    );
    vi.setSystemTime(performance.timeOrigin + RELOAD_GUARD_MS + 1);
    await expect(loadChunk(missingFile)).rejects.toThrow(
      'Failed to fetch dynamically imported module',
    );
    expect(reload).not.toHaveBeenCalled();
  });

  it('reloads again for another address', async () => {
    reloaded('/sources');
    loadChunk(missingFile).then(settled, settled);
    await tick();
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('during a navigation, loads the page the visitor was going to, not the one they are leaving', async () => {
    window.history.replaceState(null, '', '/search?q=agents');
    listener({ navigation: { location: { pathname: '/saved', search: '', hash: '' } } });
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      pathname: '/search',
      search: '?q=agents',
      hash: '',
      reload,
      assign,
    });
    loadChunk(missingFile).then(settled, settled);
    await tick();
    expect(assign).toHaveBeenCalledWith('/saved');
    expect(reload).not.toHaveBeenCalled();
    expect(JSON.parse(window.sessionStorage.getItem(CHUNK_RELOAD_KEY) ?? '{}')).toMatchObject({
      address: '/saved',
    });
  });

  it('a value it did not write is no guard', async () => {
    window.sessionStorage.setItem(CHUNK_RELOAD_KEY, '/saved');
    loadChunk(missingFile).then(settled, settled);
    await tick();
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('does not reload when browser storage is blocked, so it cannot loop', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    await expect(loadChunk(missingFile)).rejects.toThrow();
    expect(reload).not.toHaveBeenCalled();
  });
});
