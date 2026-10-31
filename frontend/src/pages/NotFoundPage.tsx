import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import './notfound-v2.css';

/** Not found (ADR 0040, 8): say what happened, then two ways on: the search box in the header, or the home page. */
export default function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <section className="h2-notfound" aria-labelledby="h2-notfound-h">
      <h1 id="h2-notfound-h">{t('notFound.title')}</h1>
      <p>{t('notFound.body')}</p>
      <p><Link to="/">{t('notFound.home')}</Link></p>
    </section>
  );
}
