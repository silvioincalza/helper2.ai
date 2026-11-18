import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Skeleton, SkeletonRows } from './Skeleton';
import { RetryButton, StatePanel } from './StatePanel';

export const FIRST_ROWS = 6;

interface ListFeed {
  rows: unknown[];
  loading: boolean;
  error: boolean;
  reload: () => void;
}

function TermListSkeleton() {
  return (
    <Skeleton>
      <SkeletonRows rows={FIRST_ROWS} bars={[{ size: 'meta', width: 'full' }]} ruled />
    </Skeleton>
  );
}

export function ListState({
  feed,
  errorText,
  emptyText,
  level = 3,
  skeleton,
}: {
  feed: ListFeed;
  errorText: string;
  emptyText: string;
  level?: number;
  skeleton?: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <div aria-live="polite">
      {feed.loading && (
        <>
          <span className="sr-only">{t('common.loading')}</span>
          <div aria-busy="true">{skeleton ?? <TermListSkeleton />}</div>
        </>
      )}
      {!feed.loading && feed.error && (
        <StatePanel
          tone="error"
          level={level}
          title={errorText}
          next={<RetryButton onClick={feed.reload} />}
        />
      )}
      {!feed.loading && !feed.error && feed.rows.length === 0 && (
        <StatePanel tone="notice" level={level} title={emptyText} />
      )}
    </div>
  );
}
