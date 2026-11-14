import { useTranslation } from 'react-i18next';
import { useTitle } from '@/hooks/useTitle';
import { Sources } from '@/features/sources/Sources';

export default function SourcesPage() {
  const { t } = useTranslation();
  useTitle(t('nav.sources'));
  return <Sources />;
}
