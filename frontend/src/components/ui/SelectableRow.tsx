import type { KeyboardEventHandler, ReactNode } from 'react';
import './ui.css';

/**
 * One row of a list that drives a preview: a real button. The selected row carries `aria-current` and the
 * `row-selected-line` outline, so selection is never shown by colour alone. The list sets [tabIndex] and
 * [onKeyDown] when the arrow keys move between rows (one row in the tab order), and [id] to move focus to a row.
 * [labelledBy] and [describedBy] name the row by part of its content (the title first) and describe it by the rest.
 */
export function SelectableRow({ id, selected, onSelect, children, tabIndex, onKeyDown, labelledBy, describedBy }: {
  id?: string;
  labelledBy?: string | undefined;
  describedBy?: string | undefined;
  selected: boolean;
  onSelect: () => void;
  children: ReactNode;
  tabIndex?: number;
  onKeyDown?: KeyboardEventHandler<HTMLButtonElement>;
}) {
  return (
    <button id={id} type="button" aria-labelledby={labelledBy} aria-describedby={describedBy} className="h2-select-row" aria-current={selected ? 'true' : undefined} tabIndex={tabIndex}
      onClick={onSelect} onKeyDown={onKeyDown}>
      {children}
    </button>
  );
}
