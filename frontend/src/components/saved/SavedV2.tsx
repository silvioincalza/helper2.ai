import { useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResourceType } from '@/api/types';
import { ExternalLink } from '@/components/ExternalLink';
import { TypeTag } from '@/components/ui/TypeTag';
import { useBookmarks } from '@/context/BookmarksContext';
import { formatDate, hostname } from '@/lib/format';
import './saved-v2.css';

/**
 * Design v2 saved page (ADR 0040, 10): type tabs when there is more than one type, one row per saved resource
 * (type, host, saved date, title, the original link, Remove). A saved item keeps no quote, so the row shows none.
 */
export function SavedV2() {
  const { t, i18n } = useTranslation();
  const { items, remove, mode, storageFailed, serverError } = useBookmarks();
  const [picked, setType] = useState<ResourceType | null>(null);
  const types = [...new Set(items.map((b) => b.type).filter((x): x is ResourceType => x != null))];
  // Removing the last item of the picked type falls back to All, so the other items never hide behind an empty tab.
  const type = picked && types.includes(picked) ? picked : null;
  const shown = type ? items.filter((b) => b.type === type) : items;
  return (
    <div className="h2-saved">
      <header className="h2-page-head">
        <h1>{t('saved.title')}</h1>
        <p className="h2-page-head__lede">{mode === 'server' ? t('saved.serverNote') : t('saved.localNote')}</p>
      </header>
      {storageFailed && <p role="alert" className="banner banner-warn">{t('saved.storageFailed')}</p>}
      {serverError && <p role="alert" className="banner banner-warn">{t('saved.serverError')}</p>}
      {items.length === 0 ? (
        <div className="h2-saved__empty">
          <p>{t('saved.empty')}</p>
          <Link className="btn btn-primary" to="/">{t('detail.backToSearch')}</Link>
        </div>
      ) : (
        <>
          {types.length > 1 && (
            <nav className="h2-tabs" aria-label={t('savedV2.types')}>
              <button type="button" className="h2-tab" aria-pressed={type === null} onClick={() => setType(null)}>
                {t('resultsV2.allTypes')} <span className="h2-tab__n">{items.length}</span>
              </button>
              {types.map((ty) => (
                <button key={ty} type="button" className="h2-tab" aria-pressed={type === ty} onClick={() => setType(ty)}>
                  {t(`type.${ty}`)} <span className="h2-tab__n">{items.filter((b) => b.type === ty).length}</span>
                </button>
              ))}
            </nav>
          )}
          <ul className="h2-saved__list">
            {shown.map((b) => (
              <li key={b.resourceId} className="h2-saved__row">
                <div className="h2-saved__meta">
                  {b.type && <TypeTag type={b.type} />}
                  <span className="h2-saved__host">{hostname(b.url)}</span>
                  <span>{t('saved.savedAt', { date: formatDate(b.savedAt, i18n.language) })}</span>
                </div>
                <h2 className="h2-saved__title"><Link to={`/resource/${b.resourceId}`}>{b.title}</Link></h2>
                <div className="h2-saved__actions">
                  <ExternalLink url={b.url} showHost={false} />
                  <button type="button" className="btn" aria-label={t('saved.removeLabel', { title: b.title })} onClick={() => remove(b.resourceId)}>
                    {t('saved.remove')}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
