import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, deleteMyAccount } from '@/api/client';
import { useMe } from '@/context/MeContext';
import { useTitle } from '@/hooks/useTitle';
import './text.css';

/**
 * One display setting, kept with the account: whether the Home radar moves between its sources. The box shows the
 * server's value; a change is sent at once and GET /me is read again, so Home sees it.
 */
function RadarMotionSetting({ on, onSaved }: { on: boolean; onSaved: () => void }) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const change = (radarMotion: boolean) => {
    setBusy(true);
    setFailed(false);
    api
      .changePreferences({ radarMotion })
      .then(onSaved, () => setFailed(true))
      .finally(() => setBusy(false));
  };
  return (
    <section aria-labelledby="account-display-h">
      <h2 id="account-display-h">{t('account.displayTitle')}</h2>
      <p className="h2-check">
        <input
          id="account-radar-motion"
          type="checkbox"
          checked={on}
          disabled={busy}
          aria-describedby="account-radar-motion-text"
          onChange={(e) => change(e.target.checked)}
        />
        <label htmlFor="account-radar-motion">{t('account.radarMotion')}</label>
      </p>
      <p id="account-radar-motion-text">{t('account.radarMotionText')}</p>
      {failed && (
        <p role="alert" className="banner banner-warn">
          {t('account.saveFailed')}
        </p>
      )}
    </section>
  );
}

/**
 * The signed-in user's account: who they are signed in as, the display setting, and deleting the account after a
 * confirmation.
 */
export default function AccountPage() {
  const { t } = useTranslation();
  const { me, loaded, refresh } = useMe();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const wasConfirming = useRef(false);
  useTitle(t('account.title'));
  // An open dialog takes focus on Cancel (the safe choice) and closes on Escape; closed, focus returns to its opener.
  useEffect(() => {
    if (confirming) {
      cancelButton.current?.focus();
      const onKey = (e: KeyboardEvent) => {
        if (e.key === 'Escape') setConfirming(false);
      };
      document.addEventListener('keydown', onKey);
      wasConfirming.current = true;
      return () => document.removeEventListener('keydown', onKey);
    }
    if (wasConfirming.current) opener.current?.focus();
    wasConfirming.current = false;
    return undefined;
  }, [confirming]);

  if (!loaded) return <p role="status">{t('common.loading')}</p>;
  if (!me.authenticated) {
    return (
      <div className="h2-text-page">
        <h1>{t('account.title')}</h1>
        <p>
          {t('account.signedOut')} <Link to="/privacy">{t('nav.privacy')}</Link>
        </p>
      </div>
    );
  }

  const cancel = () => setConfirming(false);
  const confirm = () => {
    setBusy(true);
    setFailed(false);
    deleteMyAccount().catch(() => {
      setBusy(false);
      setConfirming(false);
      setFailed(true);
    });
  };

  return (
    <article className="text-page h2-text-page">
      <h1>{t('account.title')}</h1>
      <p>{t('account.signedInAs', { name: me.displayName ?? t('nav.signedIn') })}</p>
      <RadarMotionSetting on={me.preferences?.radarMotion ?? true} onSaved={refresh} />
      <h2>{t('account.deleteTitle')}</h2>
      <p>
        {t('account.deleteText')} <Link to="/privacy">{t('account.privacyLink')}</Link>
      </p>
      {failed && (
        <p role="alert" className="banner banner-warn">
          {t('account.failed')}
        </p>
      )}
      <button
        ref={opener}
        type="button"
        className="btn"
        onClick={() => setConfirming(true)}
        disabled={confirming}
      >
        {t('account.delete')}
      </button>
      {confirming && (
        <div
          role="alertdialog"
          aria-modal="false"
          aria-labelledby="account-confirm-title"
          aria-describedby="account-confirm-text"
          className="h2-dialog"
        >
          <h2 id="account-confirm-title">{t('account.confirmTitle')}</h2>
          <p id="account-confirm-text">{t('account.confirmText')}</p>
          <div className="h2-dialog__actions">
            <button
              ref={cancelButton}
              type="button"
              className="btn"
              onClick={cancel}
              disabled={busy}
            >
              {t('account.cancel')}
            </button>
            <button type="button" className="btn btn-primary" onClick={confirm} disabled={busy}>
              {t('account.confirm')}
            </button>
          </div>
        </div>
      )}
    </article>
  );
}
