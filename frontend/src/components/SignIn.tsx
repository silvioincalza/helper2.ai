import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router';
import { loginUrl } from '@/api/client';
import { useMe } from '@/context/MeContext';
import { rememberSignInReturn } from '@/lib/signInReturn';

/** What the sign-in page gets from the link that opened it: the page to come back to after sign-in. */
export interface SignInFrom {
  from: string;
}

/**
 * "Sign in" in the header: opens the sign-in page (design v4, ADR 0028), which says why and lists the ways to sign
 * in. It carries the current page, so the visitor comes back to it. Nothing when the site offers no provider or the
 * visitor is signed in.
 */
export function SignInLink({ className }: { className?: string }) {
  const { t } = useTranslation();
  const { me } = useMe();
  const { pathname, search } = useLocation();
  if (me.authenticated || me.loginProviders.length === 0) return null;
  const state: SignInFrom = { from: pathname + search };
  return (
    <Link className={className} to="/signin" state={state}>
      {t('nav.signIn')}
    </Link>
  );
}

/**
 * Sign-in for the configured providers, as direct links: on the Saved page for visitors, on the admin page for the
 * owner (ADR 0024). Nothing when the site offers no provider or the visitor is signed in. The current page is
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
    return (
      <a className={className} href={loginUrl(only.id)} onClick={remember}>
        {t('nav.signInWith', { provider: only.name })}
      </a>
    );
  }
  if (me.loginProviders.length > 1) {
    return (
      <details className="menu">
        <summary className="btn">{t('nav.signIn')}</summary>
        <ul className="plain-list menu-list">
          {me.loginProviders.map((p) => (
            <li key={p.id}>
              <a href={loginUrl(p.id)} onClick={remember}>
                {p.name}
              </a>
            </li>
          ))}
        </ul>
      </details>
    );
  }
  return null;
}
