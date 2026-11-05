import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { Robots } from '@/components/Robots';
import './text.css';

/**
 * Not found (ADR 0040, 8; design v4, ADR 0042): say what happened, then two ways on: the search box in the header, or
 * the home page. The server already answered 404 and wrote the head (#169); this is only what the page shows.
 */
export default function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <div className="h2-prose h2-notfound">
      <Robots content="noindex" />
      <p className="h2-label">{t('notFound.label')}</p>
      <h1>{t('notFound.title')}</h1>
      <p>{t('notFound.body')}</p>
      <Link to="/" className="btn">
        {t('notFound.home')}
      </Link>
    </div>
  );
}
