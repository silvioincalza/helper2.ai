import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router';
import { loginUrl } from '@/api/client';
import { useMe } from '@/context/MeContext';
import { rememberSignInReturn } from '@/lib/signInReturn';

/**
 * Sign-in for the configured providers: in the header and on the Saved page for visitors, on the admin page for the
 * owner (ADR 0035). Nothing when the site offers no provider or the visitor is signed in. The current page is
 * remembered, so the visitor comes back to it.
 */
export function SignIn({ className = 'btn btn-primary' }: { className?: string }) {
  const { t } = useTranslation();
  const { me } = useMe();
  const { pathname, search } = useLocation();
  if (me.authenticated) return null;
  const remember = () => rememberSignInReturn(pathname + search);
  const [only] = me.loginProviders;
  if (me.loginProviders.length === 1 && only) {
    return <a className={className} href={loginUrl(only.id)} onClick={remember}>{t('nav.signInWith', { provider: only.name })}</a>;
  }
  if (me.loginProviders.length > 1) {
    return (
      <details className="menu">
        <summary className="btn">{t('nav.signIn')}</summary>
        <ul className="plain-list menu-list">
          {me.loginProviders.map((p) => <li key={p.id}><a href={loginUrl(p.id)} onClick={remember}>{p.name}</a></li>)}
        </ul>
      </details>
    );
  }
  return null;
}
