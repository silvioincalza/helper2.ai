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
  /** Reads GET /me again, after something spent a daily allowance (a guide answer). */
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

  const [version, setVersion] = useState(0);
  const refresh = useCallback(() => setVersion((v) => v + 1), []);
  useEffect(() => {
    const ac = new AbortController();
    api.getMe(ac.signal).then(
      (me) => setState({ me, loaded: true }),
      () => {
        if (!ac.signal.aborted) setState({ me: ANON, loaded: true });
      },
    );
    return () => ac.abort();
  }, [version]);

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
