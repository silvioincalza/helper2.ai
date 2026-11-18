import { useCallback, useState } from 'react';

export function useRetoldReasons(page: unknown) {
  const [retold, setRetold] = useState<{ of: unknown; reasons: Record<string, string> }>({
    of: undefined,
    reasons: {},
  });
  const reasons = retold.of === page ? retold.reasons : {};
  const retell = useCallback(
    (id: string, reason: string) =>
      setRetold((r) => {
        const base = r.of === page ? r.reasons : {};
        return r.of === page && base[id] === reason
          ? r
          : { of: page, reasons: { ...base, [id]: reason } };
      }),
    [page],
  );
  return { reasons, retell };
}
