import { CHUNK_RELOAD_KEY, loadChunk } from '@/lib/staleChunk';

describe('loading a page file', () => {
  const reload = vi.fn();
  const settled = vi.fn();

  beforeEach(() => {
    window.sessionStorage.clear();
    reload.mockReset();
    settled.mockReset();
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      pathname: '/saved',
      search: '',
      reload,
    });
  });

  afterEach(() => vi.restoreAllMocks());

  const missingFile = () =>
    Promise.reject(
      new TypeError('Failed to fetch dynamically imported module: /assets/SavedPage-old.js'),
    );

  it('returns the module and forgets an earlier reload', async () => {
    window.sessionStorage.setItem(CHUNK_RELOAD_KEY, '/saved');
    await expect(loadChunk(() => Promise.resolve({ default: 'page' }))).resolves.toEqual({
      default: 'page',
    });
    expect(window.sessionStorage.getItem(CHUNK_RELOAD_KEY)).toBeNull();
    expect(reload).not.toHaveBeenCalled();
  });

  it('reloads the tab once when the file of an older release is gone, and keeps the old page meanwhile', async () => {
    loadChunk(missingFile).then(settled, settled);
    await new Promise((r) => setTimeout(r, 0));
    expect(reload).toHaveBeenCalledTimes(1);
    expect(window.sessionStorage.getItem(CHUNK_RELOAD_KEY)).toBe('/saved');
    expect(settled).not.toHaveBeenCalled();
  });

  it('does not reload a second time for the same address: the error goes on', async () => {
    window.sessionStorage.setItem(CHUNK_RELOAD_KEY, '/saved');
    await expect(loadChunk(missingFile)).rejects.toThrow(
      'Failed to fetch dynamically imported module',
    );
    expect(reload).not.toHaveBeenCalled();
  });

  it('reloads again for another address', async () => {
    window.sessionStorage.setItem(CHUNK_RELOAD_KEY, '/sources');
    loadChunk(missingFile).then(settled, settled);
    await new Promise((r) => setTimeout(r, 0));
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
