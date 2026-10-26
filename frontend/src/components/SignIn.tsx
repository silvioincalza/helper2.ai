import { useTranslation } from 'react-i18next';
import { loginUrl } from '@/api/client';
import { useMe } from '@/context/MeContext';

/** Sign-in for the configured providers. Shown on the admin page only: the public site needs no account. */
export function SignIn() {
  const { t } = useTranslation();
  const { me } = useMe();
  if (me.authenticated) return null;
  const [only] = me.loginProviders;
  if (me.loginProviders.length === 1 && only) {
    return <a className="btn btn-primary" href={loginUrl(only.id)}>{t('nav.signInWith', { provider: only.name })}</a>;
  }
  if (me.loginProviders.length > 1) {
    return (
      <details className="menu">
        <summary className="btn">{t('nav.signIn')}</summary>
        <ul className="plain-list menu-list">
          {me.loginProviders.map((p) => <li key={p.id}><a href={loginUrl(p.id)}>{p.name}</a></li>)}
        </ul>
      </details>
    );
  }
  return null;
}
