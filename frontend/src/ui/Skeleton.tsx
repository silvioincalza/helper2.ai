import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import './skeleton.css';

type BarSize = 'meta' | 'small' | 'body' | 'title' | 'heading' | 'chip' | 'control';
export type BarWidth = 'full' | 'wide' | 'half' | 'short' | 'tiny';

export interface Bar {
  size?: BarSize;
  width?: BarWidth;
}

export function SkeletonBar({ size = 'body', width = 'full' }: Bar) {
  return <span className={`h2-skel__bar is-${size} is-${width}`} />;
}

export function Skeleton({
  onTerm = false,
  className,
  children,
}: {
  onTerm?: boolean;
  className?: string;
  children: ReactNode;
}) {
  const classes = ['h2-skel', onTerm && 'is-on-term', className].filter(Boolean).join(' ');
  return (
    <div className={classes} aria-hidden="true">
      {children}
    </div>
  );
}

export function SkeletonRows({
  rows,
  bars,
  ruled = false,
  roomy = false,
}: {
  rows: number;
  bars: readonly Bar[];
  ruled?: boolean;
  roomy?: boolean;
}) {
  const classes = ['h2-skel__row', ruled && 'is-ruled', roomy && 'is-roomy']
    .filter(Boolean)
    .join(' ');
  return (
    <>
      {Array.from({ length: rows }, (_, row) => (
        <div key={row} className={classes}>
          {bars.map((bar, i) => (
            <SkeletonBar key={i} {...bar} />
          ))}
        </div>
      ))}
    </>
  );
}

export function SkeletonChips({ widths }: { widths: readonly BarWidth[] }) {
  return (
    <div className="h2-skel__chips">
      {widths.map((width, i) => (
        <SkeletonBar key={i} size="chip" width={width} />
      ))}
    </div>
  );
}

export function Loading({
  label,
  className,
  children,
}: {
  label?: string | undefined;
  className?: string;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <div className={className}>
      <p role="status" className="sr-only">
        {label ?? t('common.loading')}
      </p>
      {children}
    </div>
  );
}

export function TableSkeleton({ rows = 6, quiet = false }: { rows?: number; quiet?: boolean }) {
  const bones = (
    <Skeleton>
      <SkeletonRows rows={rows} bars={[{ size: 'meta', width: 'full' }]} ruled />
    </Skeleton>
  );
  return quiet ? bones : <Loading>{bones}</Loading>;
}
