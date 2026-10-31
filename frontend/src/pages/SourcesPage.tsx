import { useTranslation } from 'react-i18next';
import { useTitle } from '@/hooks/useTitle';
import { SourcesV2 } from '@/components/sources/SourcesV2';

export default function SourcesPage() {
  const { t } = useTranslation();
  useTitle(t('nav.sources'));
  return <SourcesV2 />;
}
