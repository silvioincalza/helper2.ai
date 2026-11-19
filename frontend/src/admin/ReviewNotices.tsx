import { useTranslation } from 'react-i18next';
import { Icon } from '@/ui/Icon';
import { resourcePath } from '@/lib/searchState';
import type { useReviewActions } from './useReviewActions';

type Actions = ReturnType<typeof useReviewActions>;

export function ReviewNotices({
  error,
  listed,
  announcement,
}: Pick<Actions, 'error' | 'listed' | 'announcement'>) {
  const { t } = useTranslation();
  return (
    <>
      {error && (
        <p role="alert" className="banner banner-warn">
          <Icon name="warn" />{' '}
          {error.kind === 'refused'
            ? t('admin.refused', { detail: error.detail })
            : t(error.kind === 'alreadyDecided' ? 'admin.alreadyDecided' : 'admin.actionFailed')}
        </p>
      )}
      {listed && (
        <p role="status" className="banner">
          <Icon name="info" /> {t('admin.review.alreadyListed', { title: listed.title })}{' '}
          <a href={resourcePath(listed.resourceId, listed.title)}>{t('admin.review.openListed')}</a>
        </p>
      )}
      <p role="status" className="sr-only">
        {announcement}
      </p>
    </>
  );
}
