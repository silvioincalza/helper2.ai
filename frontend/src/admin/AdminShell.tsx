import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet, useNavigate, useSearchParams } from 'react-router';
import { signOut } from '@/api/client';
import { MeProvider, useMe } from '@/context/MeContext';
import { takeSignInReturn } from '@/lib/signInReturn';

const isAdminPath = (path: string) => path === '/admin' || /^\/admin[/?#]/.test(path);

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

export function AdminShell() {
  const { t } = useTranslation();
  return (
    <MeProvider>
      <SignInReturn />
      <a className="skip-link" href="#main">
        {t('nav.skip')}
      </a>
      <header className="h2-wrap h2-admin__bar">
        <SignOut />
      </header>
      <main id="main" tabIndex={-1} className="h2-wrap main">
        <Outlet />
      </main>
    </MeProvider>
  );
}
