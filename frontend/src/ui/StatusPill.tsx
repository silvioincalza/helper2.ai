import type { ReactNode } from 'react';
import './ui.css';

export type StatusTone = 'ok' | 'warn' | 'next' | 'later';

export function StatusPill({ tone, children }: { tone: StatusTone; children: ReactNode }) {
  return <span className={`h2-status-pill h2-status-pill--${tone}`}>{children}</span>;
}
