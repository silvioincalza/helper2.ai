import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';

export type Decision =
  { kind: 'confirm'; value: string } | { kind: 'edit'; value: string } | { kind: 'keep' };

export interface RunDecisions {
  decisions: Readonly<Record<string, Decision>>;
  picks: Readonly<Record<string, string>>;
}

type Update = (all: RunDecisions) => RunDecisions;

interface Store {
  of: (runId: string) => RunDecisions;
  change: (runId: string, update: Update) => void;
}

const EMPTY: RunDecisions = { decisions: {}, picks: {} };

const StoreContext = createContext<Store | null>(null);

function useStore(): Store {
  const [runs, setRuns] = useState<ReadonlyMap<string, RunDecisions>>(new Map());
  const of = useCallback((runId: string) => runs.get(runId) ?? EMPTY, [runs]);
  const change = useCallback(
    (runId: string, update: Update) =>
      setRuns((all) => new Map(all).set(runId, update(all.get(runId) ?? EMPTY))),
    [],
  );
  return { of, change };
}

export function ResearchDecisionsProvider({ children }: { children: ReactNode }) {
  const store = useStore();
  return <StoreContext.Provider value={store}>{children}</StoreContext.Provider>;
}

export function useRunDecisions(runId: string): [RunDecisions, (update: Update) => void] {
  const shared = useContext(StoreContext);
  const own = useStore();
  const store = shared ?? own;
  const { change } = store;
  const update = useCallback((u: Update) => change(runId, u), [change, runId]);
  return [store.of(runId), update];
}
