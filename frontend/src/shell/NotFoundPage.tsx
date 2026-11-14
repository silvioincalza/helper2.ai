import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { Robots } from '@/shell/Robots';
import { useTitle } from '@/hooks/useTitle';
import '../features/pages/text.css';

export default function NotFoundPage() {
  const { t } = useTranslation();
  useTitle(t('notFound.title'));
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
