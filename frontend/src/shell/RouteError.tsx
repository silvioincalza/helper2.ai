import { useTranslation } from 'react-i18next';
import { Robots } from '@/shell/Robots';
import './route-error.css';

export function RouteError() {
  const { t } = useTranslation();
  return (
    <div className="h2-route-error" role="alert">
      <Robots content="noindex" />
      <p className="h2-label">{t('routeError.label')}</p>
      <h1>{t('routeError.title')}</h1>
      <p>{t('routeError.body')}</p>
      <button type="button" className="btn" onClick={() => window.location.reload()}>
        {t('routeError.reload')}
      </button>
    </div>
  );
}
