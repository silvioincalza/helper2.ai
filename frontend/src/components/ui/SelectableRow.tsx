import type { MouseEvent, ReactNode } from 'react';
import { Link, type To } from 'react-router';
import './ui.css';

/**
 * One row of a list that drives a preview. The row holds a [RowLink]: a real link that covers the whole row, so the
 * row can be opened in a new tab and crawlers find the page. The selected row gets the `row-selected-line` outline and
 * its link carries `aria-current`, so selection is never shown by colour alone.
 */
export function SelectableRow({ selected, children }: { selected: boolean; children: ReactNode }) {
  return <div className={selected ? 'h2-select-row is-selected' : 'h2-select-row'}>{children}</div>;
}

/**
 * The link of a [SelectableRow]. With [onSelect], a plain click selects the row instead of following the link; a
 * click with a modifier key (new tab, new window) and a middle click follow it as usual. Without [onSelect] it is a
 * plain link.
 */
export function RowLink({
  to,
  state,
  selected,
  onSelect,
  className,
  children,
}: {
  to: To;
  state?: unknown;
  selected: boolean;
  onSelect?: (() => void) | undefined;
  className?: string | undefined;
  children: ReactNode;
}) {
  const onClick = (e: MouseEvent<HTMLAnchorElement>) => {
    if (!onSelect || !isPlainClick(e)) return;
    e.preventDefault();
    onSelect();
  };
  return (
    <Link
      to={to}
      state={state}
      className={className ? `h2-select-row__link ${className}` : 'h2-select-row__link'}
      aria-current={selected ? 'true' : undefined}
      onClick={onClick}
    >
      {children}
    </Link>
  );
}

function isPlainClick(e: MouseEvent) {
  return e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey && !e.altKey;
}
