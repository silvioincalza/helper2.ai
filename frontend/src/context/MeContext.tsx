import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { api } from '@/api/client';
import type { Me } from '@/api/types';

/** The session as the page knows it: the daily limits are unknown until GET /me answers. */
export type Session = Omit<Me, 'limits'> & { limits?: Me['limits'] };

interface MeValue {
  me: Session;
  loaded: boolean;
  isAdmin: boolean;
  /** Reads /me again, for the counts left today after something spent one (a guide answer). */
  refresh: () => void;
}

const ANON: Session = { authenticated: false, roles: [], loginProviders: [] };
const MeContext = createContext<MeValue>({
  me: ANON,
  loaded: false,
  isAdmin: false,
  refresh: () => undefined,
});

export function MeProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<{ me: Session; loaded: boolean }>({
    me: ANON,
    loaded: false,
  });
  const [reads, setReads] = useState(0);

  useEffect(() => {
    const ac = new AbortController();
    api.getMe(ac.signal).then(
      (me) => setState({ me, loaded: true }),
      // A failed first read is a guest; a failed later read keeps what the page knows.
      () => {
        if (!ac.signal.aborted) setState((s) => (s.loaded ? s : { me: ANON, loaded: true }));
      },
    );
    return () => ac.abort();
  }, [reads]);

  const refresh = useCallback(() => setReads((n) => n + 1), []);
  const value = useMemo<MeValue>(
    () => ({
      ...state,
      isAdmin: state.me.authenticated && state.me.roles.includes('ADMIN'),
      refresh,
    }),
    [state, refresh],
  );
  return <MeContext.Provider value={value}>{children}</MeContext.Provider>;
}

export function useMe(): MeValue {
  return useContext(MeContext);
}
