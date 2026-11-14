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
import {
  clearLocalBookmarks,
  MAX_IMPORT,
  readLocalBookmarks,
  writeLocalBookmarks,
} from '@/lib/bookmarks';
import { useMe } from './MeContext';

interface BookmarksValue {
  items: Bookmark[];
  isSaved: (id: string) => boolean;
  toggle: (b: BookmarkRequest) => void;
  remove: (id: string) => void;
  mode: 'local' | 'server';
  storageFailed: boolean;
  serverError: boolean;
  loading: boolean;
  retry: () => void;
}

const Ctx = createContext<BookmarksValue | null>(null);

function withFirst(list: Bookmark[], b: Bookmark): Bookmark[] {
  return [b, ...list.filter((i) => i.resourceId !== b.resourceId)];
}

type Edit = { kind: 'save'; entry: Bookmark } | { kind: 'remove'; id: string };

function editedId(e: Edit): string {
  return e.kind === 'save' ? e.entry.resourceId : e.id;
}

function withEdit(edits: Edit[], edit: Edit): Edit[] {
  return [...edits.filter((e) => editedId(e) !== editedId(edit)), edit];
}

function withEdits(list: Bookmark[], edits: Edit[]): Bookmark[] {
  return edits.reduce(
    (acc, e) =>
      e.kind === 'save' ? withFirst(acc, e.entry) : acc.filter((b) => b.resourceId !== e.id),
    list,
  );
}

async function importInBatches(
  waiting: Bookmark[],
  keepWaiting: (left: Bookmark[]) => void,
): Promise<Bookmark[]> {
  const batch = waiting.slice(0, MAX_IMPORT);
  const left = waiting.slice(MAX_IMPORT);
  const list = await api.mergeLocalBookmarks(
    batch.map(({ savedAt: _savedAt, ...request }) => request),
  );
  keepWaiting(left);
  return left.length ? importInBatches(left, keepWaiting) : list;
}

export function BookmarksProvider({ children }: { children: ReactNode }) {
  const { me, loaded } = useMe();
  const server = loaded && me.authenticated;
  const [local, setLocal] = useState<Bookmark[]>(() => readLocalBookmarks());
  const [remote, setRemote] = useState<Bookmark[] | null>(null);
  const [edits, setEdits] = useState<Edit[]>([]);
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

  const keepWaiting = useCallback(
    (left: Bookmark[]) => {
      if (left.length) persistLocal(left);
      else if (clearLocalBookmarks()) setLocal([]);
    },
    [persistLocal],
  );

  const removeFromAccount = useCallback((gone: Bookmark) => {
    setRemote((r) => (r ?? []).filter((b) => b.resourceId !== gone.resourceId));
    api.deleteBookmark(gone.resourceId).catch(() => {
      setRemote((r) => withFirst(r ?? [], gone));
      setServerError(true);
    });
  }, []);

  const saveToAccount = useCallback((entry: Bookmark) => {
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
    const run = localList.length ? importInBatches(localList, keepWaiting) : api.listBookmarks();
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
        const pending = editsRef.current;
        if (pending.length) {
          persistLocal(withEdits(readLocalBookmarks(), pending));
          setEdits(pending.filter((e) => e.kind === 'remove'));
        }
        setServerError(true);
      },
    );
  }, [server, attempt, persistLocal, keepWaiting, saveToAccount, removeFromAccount]);

  const retry = useCallback(() => setAttempt((a) => a + 1), []);

  const items = useMemo(
    () => (server && remote !== null ? remote : withEdits(local, edits)),
    [server, remote, local, edits],
  );

  const remove = useCallback(
    (id: string) => {
      if (!server) return persistLocal(local.filter((b) => b.resourceId !== id));
      if (remote === null) return setEdits((e) => withEdit(e, { kind: 'remove', id }));
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
      if (remote === null) return setEdits((e) => withEdit(e, { kind: 'save', entry }));
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
      serverError: server && serverError,
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
