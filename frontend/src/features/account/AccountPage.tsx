import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, deleteMyAccount } from '@/api/client';
import { useMe, type Session } from '@/context/MeContext';
import { Robots } from '@/shell/Robots';
import { useTitle } from '@/hooks/useTitle';
import { clockTime } from '@/lib/format';
import '../pages/text.css';

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
      <p id="account-radar-motion-text" className="h2-help">
        {t('account.radarMotionText')}
      </p>
      {failed && (
        <p role="alert" className="h2-note">
          {t('account.saveFailed')}
        </p>
      )}
    </section>
  );
}

interface Allowance {
  remaining: number;
  limit: number;
}

function SignedIn({ me }: { me: Session }) {
  const { t } = useTranslation();
  const resets = me.limits ? clockTime(me.limits.resetsAt, false) : '';
  const left = (a: Allowance) =>
    t('account.leftOf', {
      remaining: Math.min(a.remaining, a.limit),
      limit: a.limit,
      time: resets,
    });
  const guide = me.limits?.guideQuestions;
  const answers = guide && guide.limit > 0 ? guide : null;
  const setup = me.limits?.setupGuides;
  const setupGuides = setup && setup.limit > 0 ? setup : null;
  const searches = me.limits?.webSearches.search;
  const guideSearches = me.limits?.webSearches?.guide;
  return (
    <section aria-labelledby="account-who-h">
      <h2 id="account-who-h">{t('account.signedInTitle')}</h2>
      <p>{t('account.signedInAs', { name: me.displayName ?? t('nav.signedIn') })}</p>
      <dl className="h2-facts">
        {me.displayName && (
          <div>
            <dt>{t('account.name')}</dt>
            <dd>{me.displayName}</dd>
          </div>
        )}
        {answers && (
          <div>
            <dt>{t('account.aiAnswersLeft')}</dt>
            <dd>{left(answers)}</dd>
          </div>
        )}
        {setupGuides && (
          <div>
            <dt>{t('account.setupGuidesLeft')}</dt>
            <dd>{left(setupGuides)}</dd>
          </div>
        )}
        {searches && (
          <div>
            <dt>{t('account.webSearchesLeft')}</dt>
            <dd>{left(searches)}</dd>
          </div>
        )}
        {guideSearches && guideSearches.limit > 0 && (
          <div>
            <dt>{t('account.guideWebSearchesLeft')}</dt>
            <dd>{left(guideSearches)}</dd>
          </div>
        )}
      </dl>
      <p>
        <Link to="/privacy">{t('account.privacyLink')}</Link>
      </p>
    </section>
  );
}

export default function AccountPage() {
  return (
    <>
      <Robots content="noindex, follow" />
      <Account />
    </>
  );
}

function Account() {
  const { t } = useTranslation();
  const { me, loaded, refresh } = useMe();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const wasConfirming = useRef(false);
  useTitle(t('account.title'));
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
      <div className="h2-prose h2-text-page h2-account">
        <h1>{t('account.title')}</h1>
        <p>
          {t('account.signedOut')} <Link to="/privacy">{t('nav.privacy')}</Link>
        </p>
        {me.loginProviders.length > 0 && (
          <p>
            <Link to="/signin" className="btn">
              {t('nav.signIn')}
            </Link>
          </p>
        )}
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
    <article className="h2-prose h2-text-page h2-account">
      <h1>{t('account.title')}</h1>
      <SignedIn me={me} />
      <RadarMotionSetting on={me.preferences?.radarMotion ?? true} onSaved={refresh} />
      <section className="h2-card" aria-labelledby="account-delete-h">
        <h2 id="account-delete-h">{t('account.deleteTitle')}</h2>
        <p>{t('account.deleteText')}</p>
        {failed && (
          <p role="alert" className="h2-note">
            {t('account.failed')}
          </p>
        )}
        <button
          ref={opener}
          type="button"
          className="btn btn-danger"
          onClick={() => setConfirming(true)}
          disabled={confirming}
        >
          {t('account.delete')}
        </button>
      </section>
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
            <button type="button" className="btn btn-danger-fill" onClick={confirm} disabled={busy}>
              {t('account.confirm')}
            </button>
          </div>
        </div>
      )}
    </article>
  );
}
