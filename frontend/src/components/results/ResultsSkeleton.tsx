import { useTranslation } from 'react-i18next';
import './skeleton.css';

const ROWS = [0, 1, 2, 3];

/**
 * The results list before it can show: while the results view loads, and while the first search runs. Rows of the
 * list's shape under a heading line, a screen tall, so nothing below moves when the rows come (CLS). The lines pulse
 * three times and stop (ADR 0028 (part was 0042), decision 12). One status line says what is happening.
 */
export function ResultsSkeleton() {
  const { t } = useTranslation();
  return (
    <div role="status" className="h2-results__loading">
      <p className="h2-results__loading-line">{t('results.loading')}</p>
      <div className="h2-skeleton" aria-hidden="true">
        <span className="h2-skeleton__head" />
        <ul className="plain-list">
          {ROWS.map((i) => (
            <li key={i} className="h2-skeleton__row">
              <span className="h2-skeleton__line is-short" />
              <span className="h2-skeleton__line is-title" />
              <span className="h2-skeleton__line" />
              <span className="h2-skeleton__line is-half" />
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
