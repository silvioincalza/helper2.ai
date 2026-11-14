export const CHUNK_RELOAD_KEY = 'h2.chunkReload';

export const RELOAD_GUARD_MS = 30_000;

interface LastReload {
  address: string;
  at: number;
  page?: number;
}

interface NavigatingRouter {
  subscribe(listener: (state: NavigationState) => void): () => void;
}

interface NavigationState {
  navigation: { location?: { pathname: string; search: string; hash: string } | undefined };
}

let destination: string | undefined;

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
  } catch {}
  return undefined;
}

export function claimReloadedPage(): void {
  try {
    const last = lastReload();
    if (!last || last.page !== undefined || performance.timeOrigin < last.at) return;
    const claimed: LastReload = { ...last, page: performance.timeOrigin };
    window.sessionStorage.setItem(CHUNK_RELOAD_KEY, JSON.stringify(claimed));
  } catch {}
}

function alreadyReloaded(last: LastReload | undefined, address: string): boolean {
  if (last?.address !== address) return false;
  const openedByTheReload = last.page === performance.timeOrigin;
  return openedByTheReload || Date.now() - last.at < RELOAD_GUARD_MS;
}

function reloadOnce(address: string): boolean {
  try {
    if (alreadyReloaded(lastReload(), address)) return false;
    const next: LastReload = { address, at: Date.now() };
    window.sessionStorage.setItem(CHUNK_RELOAD_KEY, JSON.stringify(next));
  } catch {
    return false;
  }
  if (address === here()) window.location.reload();
  else window.location.assign(address);
  return true;
}

export async function loadChunk<T>(load: () => Promise<T>): Promise<T> {
  try {
    return await load();
  } catch (error) {
    if (reloadOnce(destination ?? here())) return new Promise<T>(() => {});
    throw error;
  }
}
