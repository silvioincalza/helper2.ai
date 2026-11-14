import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { SearchResponse } from '@/api/types';
import { Icon } from '@/ui/Icon';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { upTo } from '@/lib/breakpoints';
import { missedSources, retryCanHelp } from '@/lib/coverage';

const PHONE = upTo('phone');

export function CoverageLine({
  res,
  retry,
  afterCount,
}: {
  res: SearchResponse;
  retry: () => void;
  afterCount: boolean;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const listId = useId();
  const phone = useMediaQuery(PHONE, false);
  if (!res.coverage.partial) return null;
  const missed = missedSources(res.sources);
  const unanswered = res.coverage.sourcesQueried - res.coverage.sourcesSucceeded;
  const canRetry = retryCanHelp(missed);
  return (
    <>
      <span className="h2-coverage">
        <span className="h2-coverage__what">
          {afterCount && <span aria-hidden="true"> · </span>}
          <Icon name="warn" />{' '}
          {phone
            ? t('results.partialLineShort', { count: unanswered })
            : t('results.partialLine', {
                missed: unanswered,
                count: res.coverage.sourcesQueried,
              })}
        </span>
        {(missed.length > 0 || canRetry) && (
          <span className="h2-coverage__next">
            {missed.length > 0 && (
              <>
                <span className="h2-coverage__dot" aria-hidden="true">
                  {' · '}
                </span>
                <button
                  type="button"
                  className="btn-text h2-coverage__which"
                  aria-expanded={open}
                  aria-controls={listId}
                  onClick={() => setOpen((o) => !o)}
                >
                  {t('results.partialWhich')}
                </button>
              </>
            )}
            {canRetry && (
              <>
                <span className="h2-coverage__dot" aria-hidden="true">
                  {' · '}
                </span>
                <button type="button" className="btn-text h2-coverage__retry" onClick={retry}>
                  {t('common.retry')}
                </button>
              </>
            )}
          </span>
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
