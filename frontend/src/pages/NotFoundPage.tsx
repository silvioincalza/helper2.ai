import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { NEW_DESIGN_ENABLED } from '@/lib/features';
import './notfound-v2.css';

export default function NotFoundPage() {
  const { t } = useTranslation();
  if (NEW_DESIGN_ENABLED) return <NotFoundV2 />;
  return (
    <div className="state-box">
      <h1 className="h-small">{t('notFound.title')}</h1>
      <p><Link to="/">{t('detail.backToSearch')}</Link></p>
    </div>
  );
}

/** Design v2 (ADR 0040, 8): say what happened, then two ways on: the search box in the header, or the home page. */
function NotFoundV2() {
  const { t } = useTranslation();
  return (
    <section className="h2-notfound" aria-labelledby="h2-notfound-h">
      <h1 id="h2-notfound-h">{t('notFound.title')}</h1>
      <p>{t('notFound.body')}</p>
      <p><Link to="/">{t('notFound.home')}</Link></p>
    </section>
  );
}
