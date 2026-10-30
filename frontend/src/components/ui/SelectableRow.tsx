import type { ReactNode } from 'react';
import './ui.css';

/**
 * One row of a list that drives a preview: a real button. The selected row carries `aria-current` and the
 * `row-selected-line` outline, so selection is never shown by colour alone.
 */
export function SelectableRow({ selected, onSelect, children }: { selected: boolean; onSelect: () => void; children: ReactNode }) {
  return (
    <button type="button" className="h2-select-row" aria-current={selected ? 'true' : undefined} onClick={onSelect}>
      {children}
    </button>
  );
}
