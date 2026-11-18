import { useTranslation } from 'react-i18next';
import { Skeleton, SkeletonBar, SkeletonRows, type Bar } from '@/ui/Skeleton';
import './skeleton.css';

const ROW: readonly Bar[] = [
  { size: 'meta', width: 'short' },
  { size: 'title', width: 'wide' },
  { size: 'body', width: 'full' },
  { size: 'body', width: 'half' },
];

export function ResultsSkeleton() {
  const { t } = useTranslation();
  return (
    <div>
      <p role="status" className="h2-results__loading-line">
        {t('results.loading')}
      </p>
      <Skeleton className="h2-results__skeleton">
        <SkeletonBar size="heading" width="half" />
        <SkeletonRows rows={4} bars={ROW} ruled roomy />
      </Skeleton>
    </div>
  );
}
