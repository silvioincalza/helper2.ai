import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { ExternalLink } from '@/components/ExternalLink';
import { useBookmarks } from '@/context/BookmarksContext';
import { useTitle } from '@/hooks/useTitle';
import { formatDate } from '@/lib/format';

export default function SavedPage() {
  const { t, i18n } = useTranslation();
  const { items, remove, mode, storageFailed, serverError } = useBookmarks();
  useTitle(t('nav.saved'));
  return (
    <div>
      <h1>{t('saved.title')}</h1>
      <p className="muted">{mode === 'server' ? t('saved.serverNote') : t('saved.localNote')}</p>
      {storageFailed && <p role="alert" className="banner banner-warn">{t('saved.storageFailed')}</p>}
      {serverError && <p role="alert" className="banner banner-warn">{t('saved.serverError')}</p>}
      {items.length === 0 ? (
        <p className="state-box">{t('saved.empty')} <Link to="/">{t('detail.backToSearch')}</Link></p>
      ) : (
        <ul className="plain-list result-list">
          {items.map((b) => (
            <li key={b.resourceId} className="card">
              <h2 className="card-title h-mid"><Link to={`/resource/${b.resourceId}`}>{b.title}</Link></h2>
              <p className="muted small">
                {b.type ? t(`type.${b.type}`) : t('common.unknown')} · {t('saved.savedAt', { date: formatDate(b.savedAt, i18n.language) })}
              </p>
              <div className="card-actions">
                <ExternalLink url={b.url} />
                <button type="button" className="btn" aria-label={t('saved.removeLabel', { title: b.title })} onClick={() => remove(b.resourceId)}>
                  {t('saved.remove')}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
