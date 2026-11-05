import { Link, NavLink, Outlet, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { SignIn } from '@/components/SignIn';
import { useMe } from '@/context/MeContext';
import { StatePanel } from '@/components/ui/StatePanel';
import { useTitle } from '@/hooks/useTitle';
import './admin.css';

/** The back office's parts, in the order of the sub-navigation. The overview is the landing page. */
const PARTS = [
  { to: '/admin', key: 'overview', end: true },
  { to: '/admin/review', key: 'review', end: false },
  { to: '/admin/runs', key: 'runs', end: false },
  { to: '/admin/examples', key: 'examples', end: false },
  { to: '/admin/audit', key: 'audit', end: false },
] as const;

/**
 * The admin back office (ADR 0023, ADR 0035): sign-in and the ADMIN check once for every part, then a sub-navigation
 * and the part on its own route, so every view has a link (the overview links into a filtered queue). No admin
 * endpoint is called before the role is known.
 */
export default function AdminLayout() {
  const { t } = useTranslation();
  const { me, isAdmin, loaded } = useMe();
  const [params] = useSearchParams();
  useTitle(t('nav.admin'));

  if (!loaded) return <p role="status">{t('common.loading')}</p>;
  if (!isAdmin && !me.authenticated && me.loginProviders.length > 0) {
    return (
      <div className="h2-admin">
        <header className="h2-page-head">
          <h1>{t('admin.title')}</h1>
          <p className="h2-page-head__lede">{t('admin.signInPrompt')}</p>
        </header>
        {params.get('signin') === 'failed' && (
          <p role="alert" className="banner banner-warn">
            {t('admin.signInFailed')}
          </p>
        )}
        <div className="h2-admin__tools">
          <SignIn />
        </div>
      </div>
    );
  }
  if (!isAdmin) {
    return (
      <div className="h2-admin">
        <header className="h2-page-head">
          <h1>{t('admin.title')}</h1>
        </header>
        <StatePanel
          tone="error"
          level={2}
          title={t('admin.forbidden')}
          next={
            <Link to="/" className="btn">
              {t('notFound.home')}
            </Link>
          }
        />
      </div>
    );
  }
  return (
    <div className="h2-admin">
      <header className="h2-page-head">
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
