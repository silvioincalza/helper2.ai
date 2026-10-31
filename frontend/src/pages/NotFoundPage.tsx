import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { StatePanel } from '@/components/ui/StatePanel';
import { useTitle } from '@/hooks/useTitle';

/** Any address the app does not know (ADR 0040, 8): says so, and the next step is the home page or the box above. */
export default function NotFoundPage() {
  const { t } = useTranslation();
  useTitle(t('notFound.title'));
  return (
    <StatePanel tone="notice" level={1} title={t('notFound.title')} next={<Link to="/" className="btn btn-primary">{t('notFound.home')}</Link>}>
      <p>{t('notFound.body')}</p>
    </StatePanel>
  );
}
