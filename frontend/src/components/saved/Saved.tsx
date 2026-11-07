import { useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import type { ResourceType } from '@/api/types';
import { ExternalLink } from '@/components/ExternalLink';
import { ListState } from '@/components/home/listParts';
import { TermCommand } from '@/components/home/TermList';
import { useBookmarks } from '@/context/BookmarksContext';
import { useMe } from '@/context/MeContext';
import { SignIn } from '@/components/SignIn';
import { displayHost, formatDate } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';
import '@/components/ui/term.css';
import './saved.css';

/**
 * Saved page (ADR 0028, design v4 in ADR 0028): `$ helper2 saved · N` next to the title, who keeps the list, type
 * chips when there is more than one type, then one row per saved resource in the terminal style (type, host, saved
 * date in Geist Mono, the title in Geist, the original link, Remove). Loading, the sync error and the empty list are
 * TermList's states. A saved item keeps no quote, so the row shows none.
 */
export function Saved() {
  const { t, i18n } = useTranslation();
  const { items, remove, mode, storageFailed, serverError, loading, retry } = useBookmarks();
  const { me } = useMe();
  // Signed out on a site that offers sign-in: the list lives in this browser, an account keeps it everywhere.
  const canSignIn = mode !== 'server' && !me.authenticated && me.loginProviders.length > 0;
  const [picked, setType] = useState<ResourceType | null>(null);
  const types = [...new Set(items.map((b) => b.type).filter((x): x is ResourceType => x != null))];
  // Removing the last item of the picked type falls back to All, so the other items never hide behind an empty tab.
  const type = picked && types.includes(picked) ? picked : null;
  const shown = type ? items.filter((b) => b.type === type) : items;
  const ready = !loading;
  const feed = {
    rows: ready ? items : [],
    total: items.length,
    now: Number.NaN,
    loading,
    error: serverError,
    reload: retry,
  };
  return (
    <div className="v4 v4-term h2-saved">
      <header className="v4-term__head">
        <div className="v4-term__bar">
          <h1>{t('saved.title')}</h1>
          <TermCommand command="helper2 saved" total={ready ? items.length : null} />
        </div>
        <p className="v4-term__box">
          {mode === 'server'
            ? t('saved.serverNote')
            : canSignIn
              ? t('saved.localNoteSignIn')
              : t('saved.localNote')}
        </p>
        {canSignIn && (
          <div className="h2-saved__signin">
            <SignIn className="btn" />
          </div>
        )}
      </header>
      {storageFailed && (
        <p role="alert" className="v4-term__state is-error">
          <span aria-hidden="true">×</span> {t('saved.storageFailed')}
        </p>
      )}
      <ListState
        feed={feed}
        level={2}
        errorText={t('saved.serverError')}
        emptyText={t('saved.empty')}
      />
      {ready && !serverError && items.length === 0 && (
        <p className="h2-saved__next">
          <span>{t('saved.emptyHint')}</span>
          <Link className="btn" to="/">
            {t('detail.backToSearch')}
          </Link>
        </p>
      )}
      {ready && items.length > 0 && (
        <>
          {types.length > 1 && (
            <nav className="chips" aria-label={t('saved.types')}>
              <button
                type="button"
                className="chip"
                aria-pressed={type === null}
                onClick={() => setType(null)}
              >
                {t('results.allTypes')} <span className="chip__n">{items.length}</span>
              </button>
              {types.map((ty) => (
                <button
                  key={ty}
                  type="button"
                  className="chip"
                  aria-pressed={type === ty}
                  onClick={() => setType(ty)}
                >
                  {t(`type.${ty}`)}{' '}
                  <span className="chip__n">{items.filter((b) => b.type === ty).length}</span>
                </button>
              ))}
            </nav>
          )}
          <ul className="h2-saved__list" aria-label={t('saved.title')}>
            {shown.map((b) => (
              <li key={b.resourceId} className="v4-row h2-saved__row">
                <div className="h2-saved__main">
                  <p className="h2-saved__meta">
                    {b.type && <span className="h2-saved__type">{t(`type.${b.type}`)}</span>}
                    <span className="h2-saved__host">{displayHost(b.url)}</span>
                    <span>
                      {t('saved.savedAt', { date: formatDate(b.savedAt, i18n.language) })}
                    </span>
                  </p>
                  <h2 className="h2-saved__title">
                    <Link to={resourcePath(b.resourceId, b.title)}>{b.title}</Link>
                  </h2>
                </div>
                <div className="h2-saved__actions">
                  <ExternalLink url={b.url} showHost={false} />
                  <button
                    type="button"
                    className="btn"
                    aria-label={t('saved.removeLabel', { title: b.title })}
                    onClick={() => remove(b.resourceId)}
                  >
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
