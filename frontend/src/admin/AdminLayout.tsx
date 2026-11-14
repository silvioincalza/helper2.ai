import './adminText';
import { NavLink, Outlet, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { signOut } from '@/api/client';
import { SignIn } from '@/features/account/SignIn';
import { useMe } from '@/context/MeContext';
import { StatePanel } from '@/ui/StatePanel';
import { useTitle } from '@/hooks/useTitle';
import '@/ui/term.css';
import './admin.css';

const PARTS = [
  { to: '/admin', key: 'overview', end: true },
  { to: '/admin/review', key: 'review', end: false },
  { to: '/admin/runs', key: 'runs', end: false },
  { to: '/admin/enrichment', key: 'enrichment', end: false },
  { to: '/admin/examples', key: 'examples', end: false },
  { to: '/admin/audit', key: 'audit', end: false },
  { to: '/admin/settings', key: 'settings', end: false },
] as const;

export default function AdminLayout() {
  const { t } = useTranslation();
  const { me, isAdmin, loaded, signInAgain } = useMe();
  const [params] = useSearchParams();
  useTitle(t('nav.admin'));

  if (!loaded)
    return (
      <div className="h2-app h2-term h2-admin">
        <p role="status" className="h2-term__state">
          {t('common.loading')}
        </p>
      </div>
    );
  if (!isAdmin && !me.authenticated && me.loginProviders.length > 0) {
    return (
      <div className="h2-app h2-term h2-admin">
        <header className="h2-term__head">
          <h1>{t('admin.title')}</h1>
          <p className="h2-term__lede">{t('admin.signInPrompt')}</p>
        </header>
        {params.get('signin') === 'failed' && (
          <p role="alert" className="banner banner-warn">
            {t('admin.signInFailed')}
          </p>
        )}
        {signInAgain && (
          <p role="alert" className="banner banner-warn">
            {t('admin.sessionEnded')}
          </p>
        )}
        <div className="h2-admin__tools">
          <SignIn className="btn" />
        </div>
      </div>
    );
  }
  if (!isAdmin) {
    return (
      <div className="h2-app h2-term h2-admin">
        <header className="h2-term__head">
          <h1>{t('admin.title')}</h1>
        </header>
        <StatePanel
          tone="error"
          level={2}
          title={t('admin.forbidden')}
          next={
            <button type="button" className="btn" onClick={() => void signOut()}>
              {t('nav.signOut')}
            </button>
          }
        />
      </div>
    );
  }
  return (
    <div className="h2-app h2-term h2-admin">
      <header className="h2-term__head">
        <h1>{t('admin.title')}</h1>
      </header>
      <nav aria-label={t('admin.nav.label')} className="h2-admin__nav">
        <ul>
          {PARTS.map((p) => (
            <li key={p.key}>
              <NavLink to={p.to} end={p.end}>
                {t(`admin.nav.${p.key}`)}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <Outlet />
    </div>
  );
}
