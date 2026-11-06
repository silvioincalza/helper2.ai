import { useTranslation } from 'react-i18next';
import { Robots } from '@/components/Robots';
import './route-error.css';

/**
 * A page that could not open: most often its file is from an older release and the tab already reloaded once
 * (lib/staleChunk). Say so in plain words and offer a reload; never show the stack.
 */
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
