import { useSyncExternalStore } from 'react';

/** `bp-split` (docs/design/system/tokens.json): up to this width the results page has no preview panel. */
const SPLIT = '(max-width: 860px)';

function query(): MediaQueryList | null {
  return typeof window.matchMedia === 'function' ? window.matchMedia(SPLIT) : null;
}

function subscribe(change: () => void): () => void {
  const q = query();
  q?.addEventListener('change', change);
  return () => q?.removeEventListener('change', change);
}

/** True up to `bp-split` (860 px): the selected result opens inline under its row instead of in the preview panel. */
export function useNarrow(): boolean {
  return useSyncExternalStore(subscribe, () => query()?.matches ?? false, () => false);
}
