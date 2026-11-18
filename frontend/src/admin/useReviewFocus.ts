import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type RefObject,
} from 'react';
import type { ReviewItem } from '@/api/types';
import { useBarHeight } from './useBarHeight';

function isCommandKey(e: KeyboardEvent): boolean {
  const target = e.target as HTMLElement;
  if (target.closest('.h2-admin__research')) return false;
  const box = target instanceof HTMLInputElement && target.type === 'checkbox';
  const typing =
    target.isContentEditable || (!box && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName));
  return !typing && !e.ctrlKey && !e.metaKey && !e.altKey;
}

export function commandKeys(commands: Record<string, () => void>) {
  return (e: KeyboardEvent) => {
    if (e.repeat || !isCommandKey(e)) return;
    const action = commands[e.key];
    if (!action) return;
    e.preventDefault();
    action();
  };
}

export function useReviewFocus(
  loaded: ReviewItem[],
  loading: boolean,
  heading: RefObject<HTMLHeadingElement | null>,
) {
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const focusAfterLoad = useRef(false);
  const rows = useRef(new Map<string, HTMLButtonElement>());
  const note = useRef<HTMLInputElement>(null);
  const accept = useRef<HTMLButtonElement>(null);
  const decideBar = useRef<HTMLDivElement>(null);
  const detailBox = useRef<HTMLElement>(null);
  const researchSelect = useRef<HTMLSelectElement>(null);
  const selected = loaded.find((i) => i.id === selectedId) ?? loaded[0];

  useEffect(() => {
    if (loading || !focusAfterLoad.current) return;
    focusAfterLoad.current = false;
    const row = selected && rows.current.get(selected.id);
    if (row) row.focus();
    else heading.current?.focus();
  }, [loading, selected, heading]);

  useBarHeight(
    decideBar,
    detailBox,
    '--decide-h',
    `${selected?.id}/${selected?.status}/${loading}`,
  );

  const setRow = (id: string, row: HTMLButtonElement | null) => {
    if (row) rows.current.set(id, row);
    else rows.current.delete(id);
  };
  const focusRow = (id: string) => rows.current.get(id)?.focus();
  const select = (id: string | undefined, focus: boolean) => {
    if (!id) return;
    setSelectedId(id);
    if (focus) focusRow(id);
  };
  const move = (step: number) => {
    const at = selected ? loaded.indexOf(selected) : -1;
    select(loaded[at + step]?.id, true);
  };

  return {
    selected,
    show: setSelectedId,
    select,
    move,
    setRow,
    focusRow,
    focusAfterNextLoad: () => {
      focusAfterLoad.current = true;
    },
    focusAccept: useCallback(() => accept.current?.focus(), []),
    focusNote: () => note.current?.focus(),
    focusResearchFilter: () => researchSelect.current?.focus(),
    refs: { note, accept, decideBar, detailBox, researchSelect },
  };
}
