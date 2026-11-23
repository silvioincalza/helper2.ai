import { useTranslation } from 'react-i18next';
import type { ResourceDetail } from '@/api/types';

/** The field name a report about the rating carries (UC-009 BR-003). */
export const RATING_FIELD = 'rating';

// UC-007 BR-011: one score with the number of directories it comes from, never their names; a
// resource no directory rates says so. The score is a stated figure and a formula: no AI label.
export function DirectoryRating({
  rating,
  onReport,
}: {
  rating: ResourceDetail['rating'];
  onReport: () => void;
}) {
  const { t } = useTranslation();
  return (
    <section aria-labelledby="rating-h" className="panel">
      <h2 id="rating-h">{t('resource.rating')}</h2>
      {rating ? (
        <>
          <p>
            <strong>{t('resource.ratingScore', { score: rating.score.toFixed(1) })}</strong>{' '}
            <span className="muted">
              {t('resource.ratingDirectories', { count: rating.directories })}
            </span>
          </p>
          <p className="muted small">{t('resource.ratingHow')}</p>
          <p className="small">
            <a href="#report-h" onClick={onReport}>
              {t('resource.ratingReport')}
            </a>
          </p>
        </>
      ) : (
        <p className="muted">{t('resource.ratingNone')}</p>
      )}
    </section>
  );
}
