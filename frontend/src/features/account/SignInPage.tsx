import { Link, useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import { loginUrl } from '@/api/client';
import { Robots } from '@/shell/Robots';
import type { SignInFrom } from '@/features/account/SignIn';
import { useMe } from '@/context/MeContext';
import { useTitle } from '@/hooks/useTitle';
import { rememberSignInReturn } from '@/lib/signInReturn';
import '../pages/text.css';

function useReturnPath(): string {
  const state = useLocation().state as Partial<SignInFrom> | null;
  const from = typeof state?.from === 'string' ? state.from : '/';
  return from.startsWith('/signin') ? '/' : from;
}

export default function SignInPage() {
  const { t } = useTranslation();
  useTitle(t('signInPage.title'));
  return (
    <div className="h2-prose h2-signin">
      <Robots content="noindex, follow" />
      <section className="h2-signin__why" aria-labelledby="signin-why-h">
        <h1 id="signin-why-h">{t('signInPage.title')}</h1>
        <p>{t('signInPage.lede')}</p>
        <ul className="h2-signin__reasons">
          <li>
            <span>
              <strong>{t('signInPage.savedTitle')}</strong> {t('signInPage.savedText')}
            </span>
          </li>
          <li>
            <span>
              <strong>{t('signInPage.settingsTitle')}</strong> {t('signInPage.settingsText')}
            </span>
          </li>
        </ul>
      </section>
      <Ways />
    </div>
  );
}

function Ways() {
  const { t } = useTranslation();
  const { me, loaded } = useMe();
  const back = useReturnPath();
  if (!loaded)
    return (
      <p role="status" className="h2-card">
        {t('common.loading')}
      </p>
    );
  if (me.authenticated)
    return (
      <section className="h2-card" aria-labelledby="signin-how-h">
        <h2 id="signin-how-h">{t('signInPage.signedInTitle')}</h2>
        <p>{t('signInPage.signedInText', { name: me.displayName ?? t('nav.signedIn') })}</p>
        <Link to="/account" className="btn">
          {t('nav.account')}
        </Link>
      </section>
    );
  if (me.loginProviders.length === 0)
    return (
      <section className="h2-card" aria-labelledby="signin-how-h">
        <h2 id="signin-how-h">{t('signInPage.noneTitle')}</h2>
        <p>{t('signInPage.noneText')}</p>
        <Link to="/" className="btn">
          {t('notFound.home')}
        </Link>
      </section>
    );
  return (
    <section className="h2-card" aria-labelledby="signin-how-h">
      <h2 id="signin-how-h">{t('signInPage.how')}</h2>
      {me.loginProviders.map((p, i) => (
        <a
          key={p.id}
          className={i === 0 ? 'btn btn-ink h2-signin__provider' : 'btn h2-signin__provider'}
          href={loginUrl(p.id)}
          onClick={() => rememberSignInReturn(back)}
        >
          {t('signInPage.continueWith', { provider: p.name })}
        </a>
      ))}
      <p className="h2-signin__fine">
        {t('signInPage.termsIntro')} <Link to="/terms">{t('nav.terms')}</Link>.{' '}
        {t('signInPage.privacyIntro')} <Link to="/privacy">{t('nav.privacy')}</Link>{' '}
        {t('signInPage.privacyOutro')}
      </p>
    </section>
  );
}
