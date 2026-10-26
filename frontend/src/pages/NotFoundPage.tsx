import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';

export default function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <div className="state-box">
      <h1 className="h-small">{t('notFound.title')}</h1>
      <p><Link to="/">{t('detail.backToSearch')}</Link></p>
    </div>
  );
}
