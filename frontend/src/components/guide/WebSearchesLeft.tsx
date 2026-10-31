import { useTranslation } from 'react-i18next';
import type { SearchResponse } from '@/api/types';
import { Icon } from '@/components/Icon';

type WebSearches = SearchResponse['webSearches'];

/** Visitors get 3 or 10 a day (ADR 0037); a limit this high is a test environment's ceiling (staging). */
export const TEST_CEILING = 100;

/** True when the count will be said once the feature has used a web search: the limit is known and a real one. */
export function webLineExpected(value: WebSearches | undefined): boolean {
  return !!value && value.limit > 0 && value.limit < TEST_CEILING;
}

/** The next 00:00 UTC, in the visitor's own clock (HH:mm). */
function resetTime(now = new Date()): string {
  const next = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1));
  return next.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
}

/**
 * Live web searches (Tavily) left today for one feature (ADR 0037): search and the guide count apart. Said from the
 * first one used, because the limit is small; nothing before that, and nothing when the count is unknown (null).
 * A limit of [TEST_CEILING] or more is a test environment's ceiling, not a limit a visitor plans around: the count is
 * not said, only that none is left.
 */
export function WebSearchesLeft({ feature, value }: { feature: 'search' | 'guide'; value: WebSearches | undefined }) {
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
