import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet, useNavigate, useSearchParams } from 'react-router';
import { signOut } from '@/api/client';
import { MeProvider, useMe } from '@/context/MeContext';
import { takeSignInReturn } from '@/lib/signInReturn';

/** The back office's own pages only: a stored public path has no page on the admin host. */
const isAdminPath = (path: string) => path === '/admin' || /^\/admin[/?#]/.test(path);

/**
 * After GitHub sends the administrator back to /admin, opens the page where they clicked "Sign in" (a deep link with
 * its filters, ADR 0024). A failed sign-in (?signin=failed) drops the stored page; AdminLayout says so.
 */
function SignInReturn() {
  const { me, loaded } = useMe();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const failed = params.get('signin') === 'failed';
  useEffect(() => {
    if (!loaded || (!me.authenticated && !failed)) return;
    const path = takeSignInReturn();
    if (me.authenticated && path !== null && isAdminPath(path)) navigate(path, { replace: true });
  }, [loaded, me.authenticated, failed, navigate]);
  return null;
}

/** Sign out of the admin host's own session (ADR 0024: one session per host). */
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
 * The back office web app's page frame (ADR 0032): a skip link and the main landmark, without the public site's header,
 * footer, consent banner or ads.
 */
export function AdminShell() {
  const { t } = useTranslation();
  return (
    <MeProvider>
      <SignInReturn />
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
