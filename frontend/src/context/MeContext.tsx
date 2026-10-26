import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from '@/api/client';
import type { Me } from '@/api/types';

interface MeValue {
  me: Me;
  loaded: boolean;
  isAdmin: boolean;
}

const ANON: Me = { authenticated: false, roles: [], loginProviders: [] };
const MeContext = createContext<MeValue>({ me: ANON, loaded: false, isAdmin: false });

export function MeProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<{ me: Me; loaded: boolean }>({ me: ANON, loaded: false });

  useEffect(() => {
    const ac = new AbortController();
    api.getMe(ac.signal).then(
      (me) => setState({ me, loaded: true }),
      () => {
        if (!ac.signal.aborted) setState({ me: ANON, loaded: true });
      },
    );
    return () => ac.abort();
  }, []);

  const value = useMemo<MeValue>(
    () => ({ ...state, isAdmin: state.me.authenticated && state.me.roles.includes('ADMIN') }),
    [state],
  );
  return <MeContext.Provider value={value}>{children}</MeContext.Provider>;
}

export function useMe(): MeValue {
  return useContext(MeContext);
}
