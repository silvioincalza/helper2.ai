import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { StatePanel } from '@/components/ui/StatePanel';
import './notfound-v2.css';

/** Not found (ADR 0040, 8): say what happened, then two ways on: the search box in the header, or the home page. */
export default function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <div className="h2-notfound">
      <StatePanel tone="notice" level={1} title={t('notFound.title')} next={<Link to="/" className="btn btn-primary">{t('notFound.home')}</Link>}>
        <p>{t('notFound.body')}</p>
      </StatePanel>
    </div>
  );
}
