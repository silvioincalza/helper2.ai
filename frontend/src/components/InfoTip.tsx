import { useId, useState } from 'react';
import { Icon } from './Icon';

/**
 * An (i) button whose explanation shows on hover, Enter/Space or tap, and hides on Escape or blur.
 * The text stays in the accessibility tree via aria-describedby only while shown, so it is read once.
 */
export function InfoTip({ label, children }: { label: string; children: React.ReactNode }) {
  const id = useId();
  const [open, setOpen] = useState(false);
  return (
    <span className="infotip" onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}>
      <button type="button" className="infotip-btn" aria-label={label} aria-expanded={open} aria-describedby={open ? id : undefined}
        onClick={() => setOpen(true)} onBlur={() => setOpen(false)}
        onKeyDown={(e) => { if (e.key === 'Escape') setOpen(false); }}>
        <Icon name="info" />
      </button>
      {open && <span id={id} role="tooltip" className="infotip-pop">{children}</span>}
    </span>
  );
}
