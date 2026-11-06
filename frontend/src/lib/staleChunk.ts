/**
 * A tab opened before a release still asks for the old release's page files. The new image no longer has them, so
 * the import fails (404). Loading the page again gets the new index.html and its files. The same happens during a
 * rolling update, when the page comes from a new pod and the file request goes to an old one.
 *
 * The tab reloads at most once per address: the address waits in sessionStorage (this tab only) and is removed as
 * soon as a page file loads. If the reload does not help, the error goes on to the route's error page.
 */
export const CHUNK_RELOAD_KEY = 'h2.chunkReload';

const here = () => window.location.pathname + window.location.search;

/** Starts a reload, unless this address already had one. False when no reload started. */
export function reloadOnce(): boolean {
  try {
    if (window.sessionStorage.getItem(CHUNK_RELOAD_KEY) === here()) return false;
    window.sessionStorage.setItem(CHUNK_RELOAD_KEY, here());
  } catch {
    // Storage blocked: no guard against a reload loop, so no reload. The error page offers one.
    return false;
  }
  window.location.reload();
  return true;
}

function forgetReload(): void {
  try {
    window.sessionStorage.removeItem(CHUNK_RELOAD_KEY);
  } catch {
    /* storage blocked: nothing was kept */
  }
}

/**
 * Loads a page file. On failure the tab reloads once, and the promise never settles, so the old page stays until the
 * new one is in. On a second failure, the error goes on.
 */
export async function loadChunk<T>(load: () => Promise<T>): Promise<T> {
  try {
    const mod = await load();
    forgetReload();
    return mod;
  } catch (error) {
    if (reloadOnce()) return new Promise<T>(() => {});
    throw error;
  }
}
