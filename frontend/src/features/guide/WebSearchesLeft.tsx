import { useTranslation } from 'react-i18next';
import type { SearchResponse } from '@/api/types';
import { Icon } from '@/ui/Icon';
import { clockTime } from '@/lib/format';

type WebSearches = SearchResponse['webSearches'];

const TEST_CEILING = 100;

export function webLineExpected(value: WebSearches | undefined): boolean {
  return !!value && value.limit > 0 && value.limit < TEST_CEILING;
}

function resetTime(now = new Date()): string {
  const next = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1));
  return clockTime(next.toISOString(), false);
}

export function WebSearchesLeft({
  feature,
  value,
}: {
  feature: 'search' | 'guide';
  value: WebSearches | undefined;
}) {
  const { t } = useTranslation();
  if (!value || value.limit === 0 || value.remaining >= value.limit) return null;
  if (value.limit >= TEST_CEILING && value.remaining > 0) return null;
  const where = t(`webSearches.feature.${feature}`);
  return (
    <p className={`muted small h2-web-left${value.remaining === 0 ? ' is-none' : ''}`}>
      <Icon name="info" />{' '}
      {value.remaining === 0
        ? t('webSearches.none', { feature: where, time: resetTime() })
        : t('webSearches.left', { remaining: value.remaining, limit: value.limit, feature: where })}
    </p>
  );
}
