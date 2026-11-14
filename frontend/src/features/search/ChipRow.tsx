import { useId, type ReactNode } from 'react';
import { useOverflowEdges } from '@/hooks/useOverflowEdges';

export function ChipRow({ label, children }: { label: string; children: ReactNode }) {
  const labelId = useId();
  const row = useOverflowEdges<HTMLDivElement>();
  return (
    <div
      ref={row}
      className="hints h2-hints"
      role="group"
      aria-labelledby={labelId}
      onFocus={(e) => e.target.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })}
    >
      <span id={labelId} className="meta-line">
        {label}
      </span>
      {children}
    </div>
  );
}
