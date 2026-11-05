import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { SearchResponse } from '@/api/types';
import { Icon } from '@/components/Icon';
import { missedSources, retryCanHelp } from '@/lib/coverage';

/**
 * A partial answer in one quiet line, next to the result count: "2 of 5 sources did not answer · Which?". Which? opens
 * the sources and why, in words. Try again only when a retry can bring a source back (an error, a timeout); never
 * while a source is paused. Not a live region: the count next to it already says that the list changed. Nothing when
 * every queried source answered.
 */
export function CoverageLine({
  res,
  retry,
  afterCount,
}: {
  res: SearchResponse;
  retry: () => void;
  /** The line follows the result count, so it starts with a separator. */
  afterCount: boolean;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const listId = useId();
  if (!res.coverage.partial) return null;
  const missed = missedSources(res.sources);
  return (
    <>
      <span className="h2-coverage">
        {afterCount && <span aria-hidden="true"> · </span>}
        <Icon name="warn" />{' '}
        {t('results.partialLine', {
          missed: res.coverage.sourcesQueried - res.coverage.sourcesSucceeded,
          count: res.coverage.sourcesQueried,
        })}
        {missed.length > 0 && (
          <>
            <span aria-hidden="true"> · </span>
            <button
              type="button"
              className="btn-link h2-coverage__which"
              aria-expanded={open}
              aria-controls={listId}
              onClick={() => setOpen((o) => !o)}
            >
              {t('results.partialWhich')}
            </button>
          </>
        )}
        {retryCanHelp(missed) && (
          <>
            <span aria-hidden="true"> · </span>
            <button type="button" className="btn-link h2-coverage__retry" onClick={retry}>
              {t('common.retry')}
            </button>
          </>
        )}
      </span>
      <ul id={listId} className="plain-list h2-coverage__list" hidden={!open}>
        {missed.map((m) => (
          <li key={m.sourceId}>
            <strong>{m.name}</strong>{' '}
            {t(`results.missReason.${m.reason}`, { defaultValue: t('results.missReason.SKIPPED') })}
            {m.fromCache && <> {t('results.missFromCache')}</>}
          </li>
        ))}
      </ul>
    </>
  );
}
