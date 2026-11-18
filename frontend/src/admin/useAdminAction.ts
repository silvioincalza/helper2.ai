import { useRef, useState } from 'react';
import { problemDetail } from '@/api/client';

type ActionFailure = { kind: 'refused'; detail: string } | { kind: 'failed' };

function failureOf(e: unknown): ActionFailure {
  const detail = problemDetail(e);
  return detail ? { kind: 'refused', detail } : { kind: 'failed' };
}

export function useAdminAction<Done extends { kind: string }>(reload: () => void) {
  const inFlight = useRef(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Done | ActionFailure | null>(null);

  const run = async (action: () => Promise<Done>) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    try {
      setNotice(await action());
    } catch (e) {
      setNotice(failureOf(e));
    } finally {
      inFlight.current = false;
      setBusy(false);
      reload();
    }
  };

  return { busy, notice, clearNotice: () => setNotice(null), run };
}
