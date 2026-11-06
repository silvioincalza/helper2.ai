import './adminText';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';

/**
 * "Assign categories again" (ADR 0053): starts the category backfill over the whole catalog. It answers at once and
 * runs in the background on one replica; it is idempotent, so a second click does no harm.
 */
export function CategoryBackfill() {
  const { t } = useTranslation();
  const [state, setState] = useState<'idle' | 'busy' | 'started' | 'failed'>('idle');
  const start = async () => {
    if (state === 'busy') return;
    setState('busy');
    try {
      await adminApi.backfillCategories();
      setState('started');
    } catch {
      setState('failed');
    }
  };
  return (
    <>
      <button
        type="button"
        className="btn"
        aria-disabled={state === 'busy' || undefined}
        aria-describedby="category-backfill-hint"
        onClick={() => void start()}
      >
        {t('categories.backfill')}
      </button>
      <p id="category-backfill-hint" className="muted small" role="status">
        {state === 'started'
          ? t('categories.backfillStarted')
          : state === 'failed'
            ? t('categories.backfillFailed')
            : t('categories.backfillHint')}
      </p>
    </>
  );
}
