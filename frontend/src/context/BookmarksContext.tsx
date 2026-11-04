import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { api } from '@/api/client';
import type { Bookmark, BookmarkRequest } from '@/api/types';
import { readLocalBookmarks, writeLocalBookmarks } from '@/lib/bookmarks';
import { useMe } from './MeContext';

interface BookmarksValue {
  items: Bookmark[];
  isSaved: (id: string) => boolean;
  toggle: (b: BookmarkRequest) => void;
  remove: (id: string) => void;
  /** 'local' = browser storage, 'server' = signed-in account list. */
  mode: 'local' | 'server';
  storageFailed: boolean;
  serverError: boolean;
  /** Asks the account list again after it failed to load or merge. */
  retry: () => void;
}

const Ctx = createContext<BookmarksValue | null>(null);

/** The list with `b` first, and no other entry for the same resource. */
function withFirst(list: Bookmark[], b: Bookmark): Bookmark[] {
  return [b, ...list.filter((i) => i.resourceId !== b.resourceId)];
}

export function BookmarksProvider({ children }: { children: ReactNode }) {
  const { me, loaded } = useMe();
  const server = loaded && me.authenticated;
  const [local, setLocal] = useState<Bookmark[]>(() => readLocalBookmarks());
  // null until the account list has arrived: until then, and if it never does, the browser's list is shown.
  const [remote, setRemote] = useState<Bookmark[] | null>(null);
  const [storageFailed, setStorageFailed] = useState(false);
  const [serverError, setServerError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const loadedFor = useRef(-1);

  useEffect(() => {
    if (!server || loadedFor.current === attempt) return;
    loadedFor.current = attempt;
    setServerError(false);
    const localList = readLocalBookmarks();
    const run = localList.length
      ? api
          .mergeLocalBookmarks(localList.map(({ savedAt: _savedAt, ...request }) => request))
          .then((list) => {
            // merged once: the server list is now the source of truth
            if (writeLocalBookmarks([])) setLocal([]);
            return list;
          })
      : api.listBookmarks();
    run.then(
      // Keep what was saved or removed while the list was on its way.
      (list) =>
        setRemote((r) => (r === null ? list : r.reduce((acc, b) => withFirst(acc, b), list))),
      () => setServerError(true),
    );
  }, [server, attempt]);

  const retry = useCallback(() => setAttempt((a) => a + 1), []);

  const persistLocal = useCallback((list: Bookmark[]) => {
    setLocal(list);
    setStorageFailed(!writeLocalBookmarks(list));
  }, []);

  const items = useMemo(
    () => (server && remote !== null ? remote : local),
    [server, remote, local],
  );

  const remove = useCallback(
    (id: string) => {
      if (server) {
        const gone = items.find((b) => b.resourceId === id);
        setRemote((r) => (r ?? []).filter((b) => b.resourceId !== id));
        api.deleteBookmark(id).catch(() => {
          // Put it back: the account still has it.
          if (gone) setRemote((r) => withFirst(r ?? [], gone));
          setServerError(true);
        });
      } else {
        persistLocal(local.filter((b) => b.resourceId !== id));
      }
    },
    [server, items, local, persistLocal],
  );

  const toggle = useCallback(
    (b: BookmarkRequest) => {
      const saved = items.some((i) => i.resourceId === b.resourceId);
      if (saved) return remove(b.resourceId);
      const entry: Bookmark = { ...b, savedAt: new Date().toISOString() };
      if (server) {
        // Shown as saved at once, so a second click removes it instead of saving it twice.
        setRemote((r) => withFirst(r ?? [], entry));
        api.addBookmark(b).then(
          (stored) =>
            setRemote((r) =>
              (r ?? []).map((i) => (i.resourceId === stored.resourceId ? stored : i)),
            ),
          () => {
            setRemote((r) => (r ?? []).filter((i) => i.resourceId !== b.resourceId));
            setServerError(true);
          },
        );
      } else {
        persistLocal([...local, entry]);
      }
    },
    [items, server, local, remove, persistLocal],
  );

  const value = useMemo<BookmarksValue>(
    () => ({
      items,
      isSaved: (id) => items.some((i) => i.resourceId === id),
      toggle,
      remove,
      mode: server ? 'server' : 'local',
      storageFailed,
      serverError,
      retry,
    }),
    [items, toggle, remove, server, storageFailed, serverError, retry],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useBookmarks(): BookmarksValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('BookmarksProvider missing');
  return v;
}
