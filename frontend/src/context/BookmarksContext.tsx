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
import { clearLocalBookmarks, readLocalBookmarks, writeLocalBookmarks } from '@/lib/bookmarks';
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
  /** Signed in, and the account list has not arrived yet (nor failed). */
  loading: boolean;
  /** Asks the account list again after it failed to load or merge. */
  retry: () => void;
}

const Ctx = createContext<BookmarksValue | null>(null);

/** The list with `b` first, and no other entry for the same resource. */
function withFirst(list: Bookmark[], b: Bookmark): Bookmark[] {
  return [b, ...list.filter((i) => i.resourceId !== b.resourceId)];
}

/** A save or removal made while the account list was still on its way. */
type Edit = { kind: 'save'; entry: Bookmark } | { kind: 'remove'; id: string };

function withEdits(list: Bookmark[], edits: Edit[]): Bookmark[] {
  return edits.reduce(
    (acc, e) =>
      e.kind === 'save' ? withFirst(acc, e.entry) : acc.filter((b) => b.resourceId !== e.id),
    list,
  );
}

export function BookmarksProvider({ children }: { children: ReactNode }) {
  const { me, loaded } = useMe();
  const server = loaded && me.authenticated;
  const [local, setLocal] = useState<Bookmark[]>(() => readLocalBookmarks());
  // null until the account list has arrived: until then, and if it never does, the browser's list is shown.
  const [remote, setRemote] = useState<Bookmark[] | null>(null);
  // Edits made while the list was on its way: shown at once, sent to the account once the list is here
  // (a removal sent during the merge would be undone by it), and kept in the browser if it never comes.
  const [edits, setEdits] = useState<Edit[]>([]);
  // The load effect reads the edits when the list arrives, long after its own render.
  const editsRef = useRef(edits);
  useEffect(() => {
    editsRef.current = edits;
  }, [edits]);
  const [storageFailed, setStorageFailed] = useState(false);
  const [serverError, setServerError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const loadedFor = useRef(-1);

  const persistLocal = useCallback((list: Bookmark[]) => {
    setLocal(list);
    setStorageFailed(!writeLocalBookmarks(list));
  }, []);

  const removeFromAccount = useCallback((gone: Bookmark) => {
    setRemote((r) => (r ?? []).filter((b) => b.resourceId !== gone.resourceId));
    api.deleteBookmark(gone.resourceId).catch(() => {
      // Put it back: the account still has it.
      setRemote((r) => withFirst(r ?? [], gone));
      setServerError(true);
    });
  }, []);

  const saveToAccount = useCallback((entry: Bookmark) => {
    // Shown as saved at once, so a second click removes it instead of saving it twice.
    setRemote((r) => withFirst(r ?? [], entry));
    const { savedAt: _savedAt, ...request } = entry;
    api.addBookmark(request).then(
      (stored) =>
        setRemote((r) => (r ?? []).map((i) => (i.resourceId === stored.resourceId ? stored : i))),
      () => {
        setRemote((r) => (r ?? []).filter((i) => i.resourceId !== entry.resourceId));
        setServerError(true);
      },
    );
  }, []);

  useEffect(() => {
    if (!server || loadedFor.current === attempt) return;
    loadedFor.current = attempt;
    setServerError(false);
    const localList = readLocalBookmarks();
    const run = localList.length
      ? api
          .mergeLocalBookmarks(localList.map(({ savedAt: _savedAt, ...request }) => request))
          .then((list) => {
            // merged once: the server list is now the source of truth, and the browser keeps nothing
            if (clearLocalBookmarks()) setLocal([]);
            return list;
          })
      : api.listBookmarks();
    run.then(
      (list) => {
        const pending = editsRef.current;
        setRemote(withEdits(list, pending));
        setEdits([]);
        for (const e of pending) {
          if (e.kind === 'save') saveToAccount(e.entry);
          else {
            const gone = list.find((b) => b.resourceId === e.id);
            if (gone) removeFromAccount(gone);
          }
        }
      },
      () => {
        // The browser's list stays the one shown: it takes the edits, and the next merge sends them.
        const pending = editsRef.current;
        if (pending.length) {
          persistLocal(withEdits(readLocalBookmarks(), pending));
          setEdits([]);
        }
        setServerError(true);
      },
    );
  }, [server, attempt, persistLocal, saveToAccount, removeFromAccount]);

  const retry = useCallback(() => setAttempt((a) => a + 1), []);

  const items = useMemo(
    () => (server && remote !== null ? remote : withEdits(local, edits)),
    [server, remote, local, edits],
  );

  const remove = useCallback(
    (id: string) => {
      if (!server) return persistLocal(local.filter((b) => b.resourceId !== id));
      if (remote === null) return setEdits((e) => [...e, { kind: 'remove', id }]);
      const gone = remote.find((b) => b.resourceId === id);
      if (gone) removeFromAccount(gone);
    },
    [server, remote, local, persistLocal, removeFromAccount],
  );

  const toggle = useCallback(
    (b: BookmarkRequest) => {
      const saved = items.some((i) => i.resourceId === b.resourceId);
      if (saved) return remove(b.resourceId);
      const entry: Bookmark = { ...b, savedAt: new Date().toISOString() };
      if (!server) return persistLocal([...local, entry]);
      if (remote === null) return setEdits((e) => [...e, { kind: 'save', entry }]);
      saveToAccount(entry);
    },
    [items, server, remote, local, remove, persistLocal, saveToAccount],
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
      loading: server && remote === null && !serverError,
      retry,
    }),
    [items, toggle, remove, server, remote, storageFailed, serverError, retry],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useBookmarks(): BookmarksValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('BookmarksProvider missing');
  return v;
}
