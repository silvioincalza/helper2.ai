import { useState } from 'react';
import type { ReviewItem } from '@/api/types';

export type Picks = ReadonlyMap<string, ReviewItem>;

export function openPicks(picked: Picks, list: ReviewItem[]): Picks {
  const open = new Set(list.filter((i) => i.status === 'OPEN').map((i) => i.id));
  const kept = [...picked].filter(([id]) => open.has(id));
  return kept.length === picked.size ? picked : new Map(kept);
}

export function togglePick(picked: Picks, item: ReviewItem): Picks {
  const next = new Map(picked);
  if (!next.delete(item.id)) next.set(item.id, item);
  return next;
}

export function picksAfterBulk(picked: Picks, sent: string[], failed: ReadonlySet<string>): Picks {
  return new Map([...picked].filter(([id]) => failed.has(id) || !sent.includes(id)));
}

export function useReviewSelection(loaded: ReviewItem[] | undefined) {
  const [pickedIds, setPicked] = useState<Picks>(new Map());
  const picked = loaded ? openPicks(pickedIds, loaded) : pickedIds;

  const toggle = (item: ReviewItem) => {
    if (item.status !== 'OPEN') return;
    setPicked((p) => togglePick(p, item));
  };
  const clear = () => setPicked(new Map());
  const keepFailed = (sent: string[], failed: ReadonlySet<string>) =>
    setPicked((p) => picksAfterBulk(p, sent, failed));

  return { picked, toggle, clear, keepFailed };
}
