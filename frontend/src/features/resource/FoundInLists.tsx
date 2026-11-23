import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResourceDetail } from '@/api/types';
import { resourcePath } from '@/lib/searchState';

export function FoundInLists({ foundIn }: { foundIn: ResourceDetail['foundIn'] }) {
  const { t } = useTranslation();
  if (foundIn.lists.length === 0 && foundIn.directoryLists === 0) return null;
  return (
    <section aria-labelledby="lists-h" className="panel">
      <h2 id="lists-h">{t('resource.foundIn')}</h2>
      {foundIn.lists.length > 0 && (
        <ul className="plain-list rel-list">
          {foundIn.lists.map((list) => (
            <li key={list.listId}>
              <Link to={resourcePath(list.listId, list.listTitle)}>{list.listTitle}</Link>
              {list.headingPath && (
                <>
                  {' '}
                  <span className="muted">
                    {t('resource.foundInUnder', { heading: list.headingPath })}
                  </span>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {foundIn.more > 0 && (
        <p className="muted small">{t('resource.foundInMore', { count: foundIn.more })}</p>
      )}
      {foundIn.directoryLists > 0 && (
        <p className="muted small">
          {t('resource.foundInDirectories', { count: foundIn.directoryLists })}
        </p>
      )}
    </section>
  );
}
