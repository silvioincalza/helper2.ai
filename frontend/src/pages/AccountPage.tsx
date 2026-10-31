import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { deleteMyAccount } from '@/api/client';
import { Icon } from '@/components/Icon';
import { StatePanel } from '@/components/ui/StatePanel';
import { useMe } from '@/context/MeContext';
import { useTitle } from '@/hooks/useTitle';

/**
 * The signed-in user's account, opened from their name in the header (ADR 0023): who they are signed in as, and
 * deleting the account after a confirmation. Signed out, it says there is no account; it offers no sign-in.
 */
export default function AccountPage() {
  const { t } = useTranslation();
  const { me, loaded } = useMe();
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
      const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setConfirming(false); };
      document.addEventListener('keydown', onKey);
      wasConfirming.current = true;
      return () => document.removeEventListener('keydown', onKey);
    }
    if (wasConfirming.current) opener.current?.focus();
    wasConfirming.current = false;
    return undefined;
  }, [confirming]);

  if (!loaded) return null;
  if (!me.authenticated) {
    return (
      <StatePanel tone="notice" level={1} title={t('account.title')}
        next={<Link to="/privacy">{t('account.privacyLink')}</Link>}>
        <p>{t('account.signedOut')}</p>
      </StatePanel>
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
    <div className="page account-page">
      <h1>{t('account.title')}</h1>
      <p className="lede">{t('account.signedInAs', { name: me.displayName ?? t('nav.signedIn') })}</p>
      <section className="page-part account-page__part" aria-labelledby="account-delete-h">
        <h2 id="account-delete-h" className="preview-label">{t('account.deleteTitle')}</h2>
        <p>{t('account.deleteText')} <Link to="/privacy">{t('account.privacyLink')}</Link></p>
        {failed && <p role="alert" className="warn-line"><Icon name="warn" /> {t('account.failed')}</p>}
        <button ref={opener} type="button" className="btn account-page__delete" onClick={() => setConfirming(true)} disabled={confirming}>
          {t('account.delete')}
        </button>
        {confirming && (
          <div role="alertdialog" aria-modal="false" aria-labelledby="account-confirm-title" aria-describedby="account-confirm-text"
            className="account-page__confirm">
            <h3 id="account-confirm-title" className="account-page__confirm-title">{t('account.confirmTitle')}</h3>
            <p id="account-confirm-text">{t('account.confirmText')}</p>
            <div className="account-page__actions">
              <button ref={cancelButton} type="button" className="btn" onClick={cancel} disabled={busy}>{t('account.cancel')}</button>
              <button type="button" className="btn account-page__delete" onClick={confirm} disabled={busy}>{t('account.confirm')}</button>
            </div>
          </div>
        )}
      </section>
    </div>
  );
}
