import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { api } from '@/api/client';
import type { Bookmark, ResourceType } from '@/api/types';
import { readLocalBookmarks, writeLocalBookmarks, type LocalBookmark } from '@/lib/bookmarks';
import { useMe } from './MeContext';

export interface BookmarkInput {
  resourceId: string;
  title: string;
  url: string;
  type: ResourceType | null;
}

interface BookmarksValue {
  items: Bookmark[];
  isSaved: (id: string) => boolean;
  toggle: (b: BookmarkInput) => void;
  remove: (id: string) => void;
  /** 'local' = browser storage, 'server' = signed-in account list. */
  mode: 'local' | 'server';
  storageFailed: boolean;
  serverError: boolean;
}

const Ctx = createContext<BookmarksValue | null>(null);

const toBookmark = (b: LocalBookmark): Bookmark => ({ ...b, type: b.type });

export function BookmarksProvider({ children }: { children: ReactNode }) {
  const { me, loaded } = useMe();
  const server = loaded && me.authenticated;
  const [local, setLocal] = useState<LocalBookmark[]>(() => readLocalBookmarks());
  const [remote, setRemote] = useState<Bookmark[]>([]);
  const [storageFailed, setStorageFailed] = useState(false);
  const [serverError, setServerError] = useState(false);
  const merged = useRef(false);

  useEffect(() => {
    if (!server || merged.current) return;
    merged.current = true;
    const localList = readLocalBookmarks();
    const run = localList.length
      ? api
          .addBookmarks(localList.map((b) => ({ resourceId: b.resourceId, title: b.title, url: b.url, type: b.type })))
          .then((list) => {
            // merged once: the server list is now the source of truth
            if (writeLocalBookmarks([])) setLocal([]);
            return list;
          })
      : api.listBookmarks();
    run.then(
      (list) => setRemote(list),
      () => {
        merged.current = false;
        setServerError(true);
      },
    );
  }, [server]);

  const persistLocal = useCallback((list: LocalBookmark[]) => {
    setLocal(list);
    setStorageFailed(!writeLocalBookmarks(list));
  }, []);

  const items = useMemo(() => (server ? remote : local.map(toBookmark)), [server, remote, local]);

  const remove = useCallback(
    (id: string) => {
      if (server) {
        setRemote((r) => r.filter((b) => b.resourceId !== id));
        api.deleteBookmark(id).catch(() => setServerError(true));
      } else {
        persistLocal(local.filter((b) => b.resourceId !== id));
      }
    },
    [server, local, persistLocal],
  );

  const toggle = useCallback(
    (b: BookmarkInput) => {
      const saved = items.some((i) => i.resourceId === b.resourceId);
      if (saved) return remove(b.resourceId);
      if (server) {
        api.addBookmarks([b]).then(setRemote, () => setServerError(true));
      } else {
        persistLocal([...local, { ...b, savedAt: new Date().toISOString() }]);
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
    }),
    [items, toggle, remove, server, storageFailed, serverError],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useBookmarks(): BookmarksValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('BookmarksProvider missing');
  return v;
}
