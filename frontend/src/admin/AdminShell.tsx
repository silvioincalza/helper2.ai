import { useTranslation } from 'react-i18next';
import { Outlet } from 'react-router';
import { signOut } from '@/api/client';
import { MeProvider, useMe } from '@/context/MeContext';

/** Sign out of the admin host's own session (ADR 0035: one session per host). */
function SignOut() {
  const { t } = useTranslation();
  const { me } = useMe();
  if (!me.authenticated) return null;
  return (
    <button type="button" className="btn" onClick={() => void signOut()}>
      {t('nav.signOut')}
    </button>
  );
}

/**
 * The back office web app's page frame (ADR 0069): a skip link and the main landmark, without the public site's header,
 * footer, consent banner or ads.
 */
export function AdminShell() {
  const { t } = useTranslation();
  return (
    <MeProvider>
      <a className="skip-link" href="#main">
        {t('nav.skip')}
      </a>
      <header className="v4-wrap h2-admin__bar">
        <SignOut />
      </header>
      <main id="main" tabIndex={-1} className="v4-wrap main">
        <Outlet />
      </main>
    </MeProvider>
  );
}
