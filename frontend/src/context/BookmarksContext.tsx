import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
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
  /** Who the visitor is, or the account's list, is not known yet. */
  loading: boolean;
  /** The account's list could not be loaded; [reload] asks again. */
  listFailed: boolean;
  reload: () => void;
  /** The browser refused to store the list: it lasts until the page is left. */
  storageFailed: boolean;
  /** The account refused an add or a removal; the list shows what the account holds. */
  syncFailed: boolean;
}

type ServerList = { status: 'loading' } | { status: 'failed' } | { status: 'ready'; items: Bookmark[] };

const Ctx = createContext<BookmarksValue | null>(null);

/**
 * Bookmarks (ADR 0023): in browser storage for a guest; on the account for a signed-in user, whose browser list
 * joins the account once (the server merge is idempotent) and is then cleared.
 */
export function BookmarksProvider({ children }: { children: ReactNode }) {
  const { me, loaded } = useMe();
  const server = loaded && me.authenticated;
  const [local, setLocal] = useState<Bookmark[]>(() => readLocalBookmarks());
  const [remote, setRemote] = useState<ServerList>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const [storageFailed, setStorageFailed] = useState(false);
  const [syncFailed, setSyncFailed] = useState(false);

  useEffect(() => {
    if (!server) return;
    let live = true;
    const localList = readLocalBookmarks();
    const run = localList.length
      ? api.mergeLocalBookmarks(localList.map(({ savedAt: _savedAt, ...request }) => request)).then((list) => {
          if (writeLocalBookmarks([])) setLocal([]);
          return list;
        })
      : api.listBookmarks();
    run.then(
      (items) => { if (live) setRemote({ status: 'ready', items }); },
      () => { if (live) setRemote({ status: 'failed' }); },
    );
    return () => { live = false; };
  }, [server, attempt]);

  const persistLocal = useCallback((list: Bookmark[]) => {
    setLocal(list);
    setStorageFailed(!writeLocalBookmarks(list));
  }, []);

  const items = useMemo(() => (server ? (remote.status === 'ready' ? remote.items : []) : local), [server, remote, local]);

  const remove = useCallback(
    (id: string) => {
      if (!server) return persistLocal(local.filter((b) => b.resourceId !== id));
      if (remote.status !== 'ready') return;
      const before = remote.items;
      setSyncFailed(false);
      setRemote({ status: 'ready', items: before.filter((b) => b.resourceId !== id) });
      api.deleteBookmark(id).catch(() => {
        setRemote({ status: 'ready', items: before });
        setSyncFailed(true);
      });
    },
    [server, local, remote, persistLocal],
  );

  const toggle = useCallback(
    (b: BookmarkRequest) => {
      if (items.some((i) => i.resourceId === b.resourceId)) return remove(b.resourceId);
      if (!server) return persistLocal([...local, { ...b, savedAt: new Date().toISOString() }]);
      setSyncFailed(false);
      api.addBookmark(b).then(
        (saved) => setRemote((r) => (r.status === 'ready' ? { status: 'ready', items: [saved, ...r.items] } : r)),
        () => setSyncFailed(true),
      );
    },
    [items, server, local, remove, persistLocal],
  );

  const reload = useCallback(() => {
    setRemote({ status: 'loading' });
    setAttempt((n) => n + 1);
  }, []);

  const value = useMemo<BookmarksValue>(
    () => ({
      items,
      isSaved: (id) => items.some((i) => i.resourceId === id),
      toggle,
      remove,
      mode: server ? 'server' : 'local',
      loading: !loaded || (server && remote.status === 'loading'),
      listFailed: server && remote.status === 'failed',
      reload,
      storageFailed,
      syncFailed,
    }),
    [items, toggle, remove, server, loaded, remote.status, reload, storageFailed, syncFailed],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useBookmarks(): BookmarksValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('BookmarksProvider missing');
  return v;
}
