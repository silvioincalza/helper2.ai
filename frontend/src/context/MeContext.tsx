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
import { api, isSignInAgain, SIGN_IN_AGAIN_EVENT } from '@/api/client';
import type { Me } from '@/api/types';

export type Session = Omit<Me, 'limits'> & {
  limits?: Me['limits'];
};

interface MeValue {
  me: Session;
  loaded: boolean;
  isAdmin: boolean;
  signInAgain: boolean;
  refresh: () => void;
}

const ANON: Session = { authenticated: false, roles: [], loginProviders: [] };
const MeContext = createContext<MeValue>({
  me: ANON,
  loaded: false,
  isAdmin: false,
  signInAgain: false,
  refresh: () => undefined,
});

function signedOut(me: Session): { me: Session; loaded: boolean } {
  return { me: { ...ANON, loginProviders: me.loginProviders }, loaded: true };
}

export function MeProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<{ me: Session; loaded: boolean }>({
    me: ANON,
    loaded: false,
  });
  const [reads, setReads] = useState(0);
  const [signInAgain, setSignInAgain] = useState(false);
  const sessionsEnded = useRef(0);

  useEffect(() => {
    const ac = new AbortController();
    api.getMe(ac.signal).then(
      (me) => setState({ me, loaded: true }),
      (e: unknown) => {
        if (ac.signal.aborted || (isSignInAgain(e) && sessionsEnded.current === 1)) return;
        setState((s) => (s.loaded ? s : { me: ANON, loaded: true }));
      },
    );
    return () => ac.abort();
  }, [reads]);

  const refresh = useCallback(() => setReads((n) => n + 1), []);

  useEffect(() => {
    const sessionEnded = () => {
      setSignInAgain(true);
      setState((s) => (s.me.authenticated ? signedOut(s.me) : s));
      sessionsEnded.current += 1;
      if (sessionsEnded.current === 1) refresh();
    };
    window.addEventListener(SIGN_IN_AGAIN_EVENT, sessionEnded);
    return () => window.removeEventListener(SIGN_IN_AGAIN_EVENT, sessionEnded);
  }, [refresh]);

  const value = useMemo<MeValue>(
    () => ({
      ...state,
      isAdmin: state.me.authenticated && state.me.roles.includes('ADMIN'),
      signInAgain: signInAgain && !state.me.authenticated,
      refresh,
    }),
    [state, signInAgain, refresh],
  );
  return <MeContext.Provider value={value}>{children}</MeContext.Provider>;
}

export function useMe(): MeValue {
  return useContext(MeContext);
}
