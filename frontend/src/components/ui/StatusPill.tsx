import type { ReactNode } from 'react';
import './ui.css';

/** ok: Updated, Published. warn: First seen. next: the next event. later: events after it. */
export type StatusTone = 'ok' | 'warn' | 'next' | 'later';

/** A status in words ([children]); the tone's colour only repeats it. */
export function StatusPill({ tone, children }: { tone: StatusTone; children: ReactNode }) {
  return <span className={`h2-status-pill h2-status-pill--${tone}`}>{children}</span>;
}
