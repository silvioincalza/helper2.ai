/**
 * A tab opened before a release still asks for the old release's page files. The new image no longer has them, so
 * the import fails (404). Loading the page again gets the new index.html and its files. The same happens during a
 * rolling update, when the page comes from a new pod and the file request goes to an old one.
 *
 * The tab loads the page the visitor was going to, not the one they are leaving: during a navigation the address bar
 * still shows the old page, so {@link followNavigation} keeps the destination.
 *
 * The tab reloads one address at most once in {@link RELOAD_GUARD_MS}, and never twice from the page the reload
 * opened ({@link claimReloadedPage}): the address, the time and that page wait in sessionStorage (this tab only). A
 * page file that loads does not clear them: a route can load several files (the admin layout, then its page), and
 * clearing on the first one would let a file that keeps failing reload the tab forever. If the reload does not help, the error goes on to the route's error page.
 */
export const CHUNK_RELOAD_KEY = 'h2.chunkReload';

/** Long enough for the reload to finish and fail again; short enough that a later release can reload the tab. */
export const RELOAD_GUARD_MS = 30_000;

interface LastReload {
  address: string;
  at: number;
  /** The start ({@link Performance.timeOrigin}) of the page the reload opened, once that page has claimed it. */
  page?: number;
}

/** The router's view this module needs: the address of the navigation in progress, if any. */
interface NavigatingRouter {
  subscribe(listener: (state: NavigationState) => void): () => void;
}

interface NavigationState {
  navigation: { location?: { pathname: string; search: string; hash: string } | undefined };
}

let destination: string | undefined;

/** Keeps the address of the navigation in progress, so a reload lands where the visitor was going. */
export function followNavigation(router: NavigatingRouter): () => void {
  return router.subscribe(({ navigation }) => {
    const to = navigation.location;
    destination = to ? to.pathname + to.search + to.hash : undefined;
  });
}

const here = () => window.location.pathname + window.location.search + window.location.hash;

function lastReload(): LastReload | undefined {
  const stored = window.sessionStorage.getItem(CHUNK_RELOAD_KEY);
  if (!stored) return undefined;
  try {
    const parsed: unknown = JSON.parse(stored);
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      typeof (parsed as LastReload).address === 'string' &&
      typeof (parsed as LastReload).at === 'number' &&
      ['number', 'undefined'].includes(typeof (parsed as LastReload).page)
    ) {
      return parsed as LastReload;
    }
  } catch {
    /* not ours: no guard */
  }
  return undefined;
}

/**
 * Marks this page as the one the last reload opened, when it is: the first page to start after the reload was asked
 * for. Runs once, when the page starts, before any page file loads. A later page, opened by another navigation, finds
 * the reload already claimed and gets only the time guard.
 */
export function claimReloadedPage(): void {
  try {
    const last = lastReload();
    if (!last || last.page !== undefined || performance.timeOrigin < last.at) return;
    const claimed: LastReload = { ...last, page: performance.timeOrigin };
    window.sessionStorage.setItem(CHUNK_RELOAD_KEY, JSON.stringify(claimed));
  } catch {
    /* storage blocked: loadChunk never reloads then */
  }
}

/**
 * True when the tab already reloaded [address] for this failure: a moment ago, or to open this very page. In the
 * page the reload opened, a file that fails slowly (a proxy that times out after more than {@link RELOAD_GUARD_MS})
 * still gets no second reload.
 */
function alreadyReloaded(last: LastReload | undefined, address: string): boolean {
  if (last?.address !== address) return false;
  const openedByTheReload = last.page === performance.timeOrigin;
  return openedByTheReload || Date.now() - last.at < RELOAD_GUARD_MS;
}

/** Starts a load of [address], unless the tab already reloaded it. False when no load started. */
function reloadOnce(address: string): boolean {
  try {
    if (alreadyReloaded(lastReload(), address)) return false;
    const next: LastReload = { address, at: Date.now() };
    window.sessionStorage.setItem(CHUNK_RELOAD_KEY, JSON.stringify(next));
  } catch {
    // Storage blocked: no guard against a reload loop, so no reload. The error page offers one.
    return false;
  }
  if (address === here()) window.location.reload();
  else window.location.assign(address);
  return true;
}

/**
 * Loads a page file. On failure the tab loads the destination again, once, and the promise never settles, so the old
 * page stays until the new one is in. On a second failure, the error goes on.
 */
export async function loadChunk<T>(load: () => Promise<T>): Promise<T> {
  try {
    return await load();
  } catch (error) {
    if (reloadOnce(destination ?? here())) return new Promise<T>(() => {});
    throw error;
  }
}
